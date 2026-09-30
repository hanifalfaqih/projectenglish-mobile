import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";

/**
 * Qwen TTS over the Token Plan HTTPS REST API (synchronous synthesis).
 *
 * POST {baseUrl}/api/v1/services/audio/tts/SpeechSynthesizer
 *   Authorization: Bearer <Token Plan key, server-side only>
 *   { model: "qwen-audio-3.0-tts-plus",
 *     input: { text, voice: "longanhuan_v3.6", format: "wav",
 *              sample_rate: 24000 } }
 *     → 200 { output: { audio: { url } } }
 *
 * The audio file is downloaded server-side and, when it arrives as a WAV
 * container, parsed down to raw PCM. The voice-turn route keeps returning
 * `{ format: "pcm", sampleRateHz: 24000, channels: 1, data: base64 }`, so
 * Android never sees the provider or the URL. Synthesis failure is
 * non-fatal by contract: callers degrade to text + Platform TTS.
 */

export const TTS_MODEL_ID = "qwen-audio-3.0-tts-plus";
export const TTS_VOICE_ID = "longanhuan_v3.6";
export const TTS_SAMPLE_RATE_HZ = 24000;
export const TTS_CHANNELS = 1;
export const TTS_BITS_PER_SAMPLE = 16;

export interface QwenTtsConfig {
  apiKey: string;
  baseUrl: string;
  modelId: string;
  voiceId: string;
  timeoutMs: number;
  /** Hard cap on downloaded audio bytes per utterance. */
  maxAudioBytes: number;
}

export interface TtsResult {
  /** Raw PCM bytes (16-bit mono 24 kHz). */
  pcm: Buffer;
}

/** Minimal HTTP surface; faked in tests, global fetch in production. */
export interface TtsHttpClient {
  postJson(
    url: string,
    headers: Record<string, string>,
    body: unknown,
    timeoutMs: number,
  ): Promise<{ status: number; json: unknown }>;
  getBytes(
    url: string,
    timeoutMs: number,
    maxBytes: number,
  ): Promise<Buffer>;
}

async function readWithLimit(
  response: Response,
  maxBytes: number,
): Promise<Buffer> {
  const reader = response.body?.getReader();
  if (!reader) {
    const buf = Buffer.from(await response.arrayBuffer());
    if (buf.length > maxBytes) {
      throw new ProviderError("Qwen TTS audio exceeded size limit");
    }
    return buf;
  }
  const chunks: Buffer[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => undefined);
      throw new ProviderError("Qwen TTS audio exceeded size limit");
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks);
}

function withTimeout<T>(timeoutMs: number, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  return run(controller.signal).finally(() => clearTimeout(timeoutId));
}

export const defaultTtsHttpClient: TtsHttpClient = {
  async postJson(url, headers, body, timeoutMs) {
    let response: Response;
    try {
      response = await withTimeout(timeoutMs, (signal) =>
        fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json", ...headers },
          body: JSON.stringify(body),
          signal,
        }),
      );
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new ProviderTimeoutError(
          `Qwen TTS timed out after ${timeoutMs}ms`,
          err,
        );
      }
      throw new ProviderError("Qwen TTS request failed", err);
    }
    let json: unknown = null;
    try {
      json = await response.json();
    } catch {
      throw new ProviderError("Qwen TTS returned invalid JSON");
    }
    return { status: response.status, json };
  },

  async getBytes(url, timeoutMs, maxBytes) {
    let response: Response;
    try {
      response = await withTimeout(timeoutMs, (signal) => fetch(url, { signal }));
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new ProviderTimeoutError(
          `Qwen TTS download timed out after ${timeoutMs}ms`,
          err,
        );
      }
      throw new ProviderError("Qwen TTS download failed", err);
    }
    if (!response.ok) {
      throw new ProviderError(`Qwen TTS download returned ${response.status}`);
    }
    try {
      return await readWithLimit(response, maxBytes);
    } catch (err: unknown) {
      if (
        err instanceof ProviderError ||
        err instanceof ProviderTimeoutError
      ) {
        throw err;
      }
      throw new ProviderError("Qwen TTS download failed", err);
    }
  },
};

interface TtsRestResponse {
  output?: {
    audio?: { url?: unknown; data?: unknown };
  };
  code?: unknown;
  message?: unknown;
  request_id?: unknown;
}

/**
 * Parse a WAV container and return the raw PCM payload. Validates the
 * exact format Android's AudioTrack path expects (PCM, mono, 24 kHz,
 * 16-bit) and rejects anything else instead of mislabeling bytes.
 */
export function extractPcmFromWav(wav: Buffer): Buffer {
  if (wav.length < 44 || !isWavContainer(wav)) {
    throw new ProviderError("Qwen TTS returned unexpected audio container");
  }
  const audioFormat = wav.readUInt16LE(20);
  const channels = wav.readUInt16LE(22);
  const sampleRate = wav.readUInt32LE(24);
  const bitsPerSample = wav.readUInt16LE(34);
  if (audioFormat !== 1) {
    throw new ProviderError("Qwen TTS audio is not PCM");
  }
  if (
    channels !== TTS_CHANNELS ||
    sampleRate !== TTS_SAMPLE_RATE_HZ ||
    bitsPerSample !== TTS_BITS_PER_SAMPLE
  ) {
    throw new ProviderError(
      `Qwen TTS audio format mismatch: ${channels}ch ${sampleRate}Hz ${bitsPerSample}bit`,
    );
  }
  const dataSize = wav.readUInt32LE(40);
  // Honor the declared data size when it fits; some Token Plan TTS files
  // carry a placeholder chunk size larger than the payload (streamed
  // encoding), in which case the available bytes are the payload.
  const available = wav.length - 44;
  const take =
    dataSize > 0 && dataSize <= available ? dataSize : available;
  const pcm = wav.subarray(44, 44 + take);
  if (pcm.length === 0 || pcm.length % 2 !== 0) {
    throw new ProviderError("Qwen TTS returned no audio");
  }
  return Buffer.from(pcm);
}

function isWavContainer(buffer: Buffer): boolean {
  return (
    buffer[0] === 0x52 && // R
    buffer[1] === 0x49 && // I
    buffer[2] === 0x46 && // F
    buffer[3] === 0x46 && // F
    buffer[8] === 0x57 && // W
    buffer[9] === 0x41 && // A
    buffer[10] === 0x56 && // V
    buffer[11] === 0x45 // E
  );
}

export class QwenTtsSynthesizer {
  constructor(
    private readonly config: QwenTtsConfig,
    private readonly http: TtsHttpClient = defaultTtsHttpClient,
  ) {}

  /**
   * Synthesize one complete utterance. Resolves with raw PCM bytes, or
   * throws ProviderError/ProviderTimeoutError. Never resolves partial
   * audio.
   */
  async synthesize(text: string): Promise<TtsResult> {
    if (!text || text.trim().length === 0) {
      throw new ProviderError("Qwen TTS received empty text");
    }

    return this.withTimeout(async () => {
      let audioUrl: string;
      try {
        audioUrl = await this.requestAudioUrl(text);
      } catch (err: unknown) {
        if (
          err instanceof ProviderError ||
          err instanceof ProviderTimeoutError
        ) {
          throw err;
        }
        throw new ProviderError("Qwen TTS request failed", err);
      }

      let file: Buffer;
      try {
        file = await this.http.getBytes(
          audioUrl,
          this.config.timeoutMs,
          this.config.maxAudioBytes,
        );
      } catch (err: unknown) {
        if (
          err instanceof ProviderError ||
          err instanceof ProviderTimeoutError
        ) {
          throw err;
        }
        throw new ProviderError("Qwen TTS download failed", err);
      }
      if (file.length === 0) {
        throw new ProviderError("Qwen TTS returned no audio");
      }
      return { pcm: extractPcmFromWav(file) };
    });
  }

  /** Synthesizer-level deadline: applies regardless of HTTP client impl. */
  private async withTimeout<T>(run: () => Promise<T>): Promise<T> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([
        run(),
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () =>
              reject(
                new ProviderTimeoutError(
                  `Qwen TTS timed out after ${this.config.timeoutMs}ms`,
                ),
              ),
            this.config.timeoutMs,
          );
          timer.unref?.();
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  }

  private async requestAudioUrl(text: string): Promise<string> {
    const { status, json } = await this.http.postJson(
      `${this.config.baseUrl}/api/v1/services/audio/tts/SpeechSynthesizer`,
      { Authorization: `Bearer ${this.config.apiKey}` },
      {
        model: this.config.modelId,
        input: {
          text,
          voice: this.config.voiceId,
          format: "wav",
          sample_rate: TTS_SAMPLE_RATE_HZ,
        },
      },
      this.config.timeoutMs,
    );
    if (status < 200 || status >= 300) {
      const body = json as TtsRestResponse | null;
      const detail =
        typeof body?.message === "string" && body.message.length > 0
          ? `: ${body.message.slice(0, 160)}`
          : "";
      throw new ProviderError(`Qwen TTS returned ${status}${detail}`);
    }
    const url = (json as TtsRestResponse)?.output?.audio?.url;
    if (typeof url !== "string" || url.length === 0) {
      throw new ProviderError("Qwen TTS response missing audio URL");
    }
    if (!/^https?:\/\//.test(url)) {
      throw new ProviderError("Qwen TTS returned malformed audio URL");
    }
    return url;
  }
}

export function createQwenTtsConfig(): QwenTtsConfig {
  const apiKey = process.env.DASHSCOPE_API_KEY;
  if (!apiKey) {
    throw new Error("DASHSCOPE_API_KEY environment variable is required");
  }

  return {
    apiKey,
    baseUrl:
      process.env.TTS_BASE_URL ??
      "https://token-plan.ap-southeast-1.maas.aliyuncs.com",
    modelId: process.env.TTS_MODEL_ID ?? TTS_MODEL_ID,
    voiceId: process.env.TTS_VOICE_ID ?? TTS_VOICE_ID,
    timeoutMs: Number(process.env.TTS_TIMEOUT_MS) || 60000,
    maxAudioBytes: Number(process.env.TTS_MAX_AUDIO_BYTES) || 8 * 1024 * 1024,
  };
}
