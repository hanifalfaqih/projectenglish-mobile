import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import { buildRetryFeedbackPrompt } from "./prompt.js";
import type { RetryFeedbackProviderInput } from "./schema.js";

/**
 * M12 retry-feedback provider boundary — a SEPARATE narrow interface. It does
 * NOT extend the interview LLMProvider.generateTurn nor the M11
 * FeedbackProvider. Output is untrusted structured JSON; the backend validates
 * it (shape) before persistence. No new vendor/SDK.
 */
export interface RetryFeedbackOutputRaw {
  overall?: unknown;
  whatWorked?: unknown;
  couldImprove?: unknown;
  tryNextTime?: unknown;
  professionalCommunication?: unknown;
  [key: string]: unknown;
}

export interface RetryFeedbackProvider {
  generateRetryFeedback(
    input: RetryFeedbackProviderInput,
  ): Promise<RetryFeedbackOutputRaw>;
}

export interface QwenRetryFeedbackProviderConfig {
  apiKey: string;
  baseUrl: string;
  modelId: string;
  timeoutMs: number;
}

const RETRY_FEEDBACK_OUTPUT_SCHEMA = {
  type: "object",
  properties: {
    overall: { type: "string" },
    whatWorked: { type: "string" },
    couldImprove: { type: "string" },
    tryNextTime: { type: "string" },
    professionalCommunication: {
      anyOf: [{ type: "array", items: { type: "string" } }, { type: "null" }],
    },
  },
  required: ["overall"],
  additionalProperties: false,
};

export class QwenRetryFeedbackProvider implements RetryFeedbackProvider {
  constructor(private readonly config: QwenRetryFeedbackProviderConfig) {}

  async generateRetryFeedback(
    input: RetryFeedbackProviderInput,
  ): Promise<RetryFeedbackOutputRaw> {
    const systemPrompt = buildRetryFeedbackPrompt(input);

    const body = {
      model: this.config.modelId,
      messages: [{ role: "system" as const, content: systemPrompt }],
      enable_thinking: false,
      response_format: {
        type: "json_schema",
        json_schema: {
          name: "retry_feedback_output",
          strict: true,
          schema: RETRY_FEEDBACK_OUTPUT_SCHEMA,
        },
      },
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(
      () => controller.abort(),
      this.config.timeoutMs,
    );

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
          `Retry feedback provider timed out after ${this.config.timeoutMs}ms`,
          err,
        );
      }
      throw new ProviderError("Retry feedback provider request failed", err);
    } finally {
      clearTimeout(timeoutId);
    }

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new ProviderError(
        `Retry feedback provider returned ${response.status}: ${text}`,
      );
    }

    let data: unknown;
    try {
      data = await response.json();
    } catch {
      throw new ProviderError("Retry feedback provider returned invalid JSON");
    }

    return this.extractOutput(data);
  }

  private extractOutput(data: unknown): RetryFeedbackOutputRaw {
    if (!data || typeof data !== "object") {
      throw new ProviderError(
        "Malformed retry feedback response: unexpected structure",
      );
    }
    const choices = (data as { choices?: unknown[] }).choices;
    if (!Array.isArray(choices) || choices.length === 0) {
      throw new ProviderError("Malformed retry feedback response: no choices");
    }
    const content = (choices[0] as { message?: { content?: string } })?.message
      ?.content;
    if (typeof content !== "string" || content.length === 0) {
      throw new ProviderError(
        "Malformed retry feedback response: no message content",
      );
    }
    try {
      return JSON.parse(content) as RetryFeedbackOutputRaw;
    } catch {
      throw new ProviderError(
        "Malformed retry feedback response: content is not valid JSON",
      );
    }
  }
}

export function createQwenRetryFeedbackProviderConfig(): QwenRetryFeedbackProviderConfig {
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
