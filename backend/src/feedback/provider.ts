import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";
import { buildFeedbackPrompt } from "./prompt.js";
import type { FeedbackInput } from "./schema.js";

/**
 * M11 feedback provider boundary — a SEPARATE narrow interface from the
 * interview LLMProvider. The interview generateTurn/TurnInput/TurnOutput
 * contract is untouched. Output is untrusted structured JSON; the backend
 * validates it (shape + message references) before persistence.
 */
export interface FeedbackOutputRaw {
  overall?: unknown;
  answerItems?: unknown;
  professionalCommunication?: unknown;
  [key: string]: unknown;
}

export interface FeedbackProvider {
  generateFeedback(input: FeedbackInput): Promise<FeedbackOutputRaw>;
}

export interface QwenFeedbackProviderConfig {
  apiKey: string;
  baseUrl: string;
  modelId: string;
  timeoutMs: number;
}

const FEEDBACK_OUTPUT_SCHEMA = {
  type: "object",
  properties: {
    overall: { type: "string" },
    answerItems: {
      type: "array",
      items: {
        type: "object",
        properties: {
          answerMessageId: { type: "string" },
          questionMessageId: {
            anyOf: [{ type: "string" }, { type: "null" }],
          },
          whatWorked: { type: "string" },
          couldImprove: { type: "string" },
          tryNextTime: { type: "string" },
        },
        required: ["answerMessageId"],
        additionalProperties: false,
      },
    },
    professionalCommunication: {
      anyOf: [{ type: "array", items: { type: "string" } }, { type: "null" }],
    },
  },
  required: ["overall", "answerItems"],
  additionalProperties: false,
};

export class QwenFeedbackProvider implements FeedbackProvider {
  constructor(private readonly config: QwenFeedbackProviderConfig) {}

  async generateFeedback(input: FeedbackInput): Promise<FeedbackOutputRaw> {
    const systemPrompt = buildFeedbackPrompt(input);

    const body = {
      model: this.config.modelId,
      messages: [{ role: "system" as const, content: systemPrompt }],
      enable_thinking: false,
      response_format: {
        type: "json_schema",
        json_schema: {
          name: "feedback_output",
          strict: true,
          schema: FEEDBACK_OUTPUT_SCHEMA,
        },
      },
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), this.config.timeoutMs);

    let response: Response;
    try {
      response = await fetch(`${this.config.baseUrl}/chat/completions`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${this.config.apiKey}`,
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new ProviderTimeoutError(
          `Feedback provider timed out after ${this.config.timeoutMs}ms`,
          err,
        );
      }
      throw new ProviderError("Feedback provider request failed", err);
    } finally {
      clearTimeout(timeoutId);
    }

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new ProviderError(
        `Feedback provider returned ${response.status}: ${text}`,
      );
    }

    let data: unknown;
    try {
      data = await response.json();
    } catch {
      throw new ProviderError("Feedback provider returned invalid JSON");
    }

    return this.extractOutput(data);
  }

  private extractOutput(data: unknown): FeedbackOutputRaw {
    if (!data || typeof data !== "object") {
      throw new ProviderError("Malformed feedback response: unexpected structure");
    }
    const choices = (data as { choices?: unknown[] }).choices;
    if (!Array.isArray(choices) || choices.length === 0) {
      throw new ProviderError("Malformed feedback response: no choices");
    }
    const content = (choices[0] as { message?: { content?: string } })?.message
      ?.content;
    if (typeof content !== "string" || content.length === 0) {
      throw new ProviderError("Malformed feedback response: no message content");
    }
    try {
      return JSON.parse(content) as FeedbackOutputRaw;
    } catch {
      throw new ProviderError(
        "Malformed feedback response: content is not valid JSON",
      );
    }
  }
}

export function createQwenFeedbackProviderConfig(): QwenFeedbackProviderConfig {
  const apiKey = process.env.DASHSCOPE_API_KEY;
  if (!apiKey) {
    throw new Error("DASHSCOPE_API_KEY environment variable is required");
  }
  return {
    apiKey,
    baseUrl:
      process.env.LLM_BASE_URL ??
      "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
    modelId: process.env.LLM_MODEL_ID ?? "qwen3.8-flash",
    timeoutMs: Number(process.env.LLM_TIMEOUT_MS) || 30000,
  };
}
