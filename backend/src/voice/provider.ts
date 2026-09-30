import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";

/**
 * Qwen-Audio-3.0-ASR-Flash speech recognition over the Token Plan native
 * multimodal-generation endpoint (NOT OpenAI-compatible chat — that
 * contract belongs to qwen3-asr-flash, a different model).
 *
 * Live-verified contract (HTTP 200 against Token Plan):
 *   POST {asrBaseUrl}/api/v1/services/aigc/multimodal-generation/generation
 *   headers: Authorization Bearer key, Content-Type application/json,
 *     X-DashScope-SSE disable
 *   body: { model: "qwen-audio-3.0-asr-flash",
 *     input: { messages: [{ role user, content: [{
 *       type input_audio, input_audio: { data: "data:audio/wav;base64,..." }}]}]},
 *     parameters: { format wav, sample_rate "16000" } }
 *   response: { sentence: { text, ... } } (also handles output.text and
 *     output.sentence.text shapes)
 *
 * Server-side credentials only. The key never appears in errors or logs.
 */

export interface QwenAsrProviderConfig {
  apiKey: string;
  /** e.g. https://token-plan.ap-southeast-1.maas.aliyuncs.com. */
  asrBaseUrl: string;
  modelId: string;
  timeoutMs: number;
}

interface AsrNativeResponse {
  output?: {
    text?: unknown;
    sentence?: { text?: unknown };
  };
  sentence?: { text?: unknown };
}

export class QwenAsrProvider {
  constructor(private readonly config: QwenAsrProviderConfig) {}

  /**
   * Transcribe WAV audio bytes. Returns the transcript, or null when the
   * provider returns no usable speech (blank/missing text).
   */
  async transcribe(wavBytes: Uint8Array): Promise<string | null> {
    const audio = Buffer.from(wavBytes).toString("base64");
    const body = {
      model: this.config.modelId,
      input: {
        messages: [
          {
            role: "user",
            content: [
              {
                type: "input_audio",
                input_audio: { data: `data:audio/wav;base64,${audio}` },
              },
            ],
          },
        ],
      },
      parameters: {
        format: "wav",
        sample_rate: "16000",
      },
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), this.config.timeoutMs);

    let response: Response;
    try {
      response = await fetch(
        `${this.config.asrBaseUrl}/api/v1/services/aigc/multimodal-generation/generation`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${this.config.apiKey}`,
            "X-DashScope-SSE": "disable",
          },
          body: JSON.stringify(body),
          signal: controller.signal,
        },
      );
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new ProviderTimeoutError(
          `Qwen ASR timed out after ${this.config.timeoutMs}ms`,
          err,
        );
      }
      throw new ProviderError("Qwen ASR request failed", err);
    } finally {
      clearTimeout(timeoutId);
    }

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      // Bounded body: upstream errors stay diagnosable without flooding logs.
      throw new ProviderError(
        `Qwen ASR returned ${response.status}: ${text.slice(0, 500)}`,
      );
    }

    const data = (await response.json().catch(() => null)) as unknown;
    if (data === null || typeof data !== "object") {
      throw new ProviderError("Qwen ASR returned invalid JSON");
    }

    // Priority: output.text → output.sentence.text → sentence.text.
    // The live Token Plan response uses the top-level sentence shape.
    const output = (data as AsrNativeResponse)?.output;
    const candidates = [
      output?.text,
      output?.sentence?.text,
      (data as AsrNativeResponse)?.sentence?.text,
    ];
    const text = candidates.find((c) => typeof c === "string") as
      | string
      | undefined;
    if (!text || text.trim().length === 0) {
      return null;
    }
    return text.trim();
  }
}

export function createQwenAsrProviderConfig(): QwenAsrProviderConfig {
  const apiKey = process.env.DASHSCOPE_API_KEY;
  if (!apiKey) {
    throw new Error("DASHSCOPE_API_KEY environment variable is required");
  }

  return {
    apiKey,
    asrBaseUrl:
      process.env.ASR_BASE_URL ??
      "https://token-plan.ap-southeast-1.maas.aliyuncs.com",
    modelId: process.env.ASR_MODEL_ID ?? "qwen-audio-3.0-asr-flash",
    timeoutMs: Number(process.env.LLM_TIMEOUT_MS) || 30000,
  };
}
