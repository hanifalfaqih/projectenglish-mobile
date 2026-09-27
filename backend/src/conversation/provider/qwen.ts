import { ProviderError, ProviderTimeoutError } from "../service.js";
import type { LLMProvider, TurnInput, TurnOutput } from "./index.js";

export interface QwenProviderConfig {
  apiKey: string;
  baseUrl: string;
  modelId: string;
  timeoutMs: number;
}

const TURN_OUTPUT_SCHEMA = {
  type: "object",
  properties: {
    assistantMessage: { type: "string" },
    proposal: {
      anyOf: [
        {
          type: "object",
          properties: {
            phase: {
              anyOf: [
                {
                  type: "string",
                  enum: ["intro", "experience", "deep_dive", "wrap_up"],
                },
                { type: "null" },
              ],
            },
            newTopics: {
              anyOf: [
                {
                  type: "array",
                  items: {
                    type: "object",
                    properties: {
                      label: { type: "string" },
                    },
                    required: ["label"],
                    additionalProperties: false,
                  },
                },
                { type: "null" },
              ],
            },
            coveredTopicIds: {
              anyOf: [
                {
                  type: "array",
                  items: { type: "string" },
                },
                { type: "null" },
              ],
            },
            currentTopicId: {
              anyOf: [{ type: "string" }, { type: "null" }],
            },
            relabelTopics: {
              anyOf: [
                {
                  type: "array",
                  items: {
                    type: "object",
                    properties: {
                      id: { type: "string" },
                      label: { type: "string" },
                    },
                    required: ["id", "label"],
                    additionalProperties: false,
                  },
                },
                { type: "null" },
              ],
            },
          },
          required: [
            "phase",
            "newTopics",
            "coveredTopicIds",
            "currentTopicId",
            "relabelTopics",
          ],
          additionalProperties: false,
        },
        { type: "null" },
      ],
    },
  },
  required: ["assistantMessage", "proposal"],
  additionalProperties: false,
};

export class QwenProvider implements LLMProvider {
  constructor(private readonly config: QwenProviderConfig) {}

  async generateTurn(input: TurnInput): Promise<TurnOutput> {
    const messages = [
      { role: "system" as const, content: input.systemPrompt },
      ...input.messages.map((m) => ({ role: m.role, content: m.content })),
    ];

    const body = {
      model: this.config.modelId,
      messages,
      enable_thinking: false,
      response_format: {
        type: "json_schema",
        json_schema: {
          name: "turn_output",
          strict: true,
          schema: TURN_OUTPUT_SCHEMA,
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
          `LLM provider timed out after ${this.config.timeoutMs}ms`,
          err,
        );
      }
      throw new ProviderError("LLM provider request failed", err);
    } finally {
      clearTimeout(timeoutId);
    }

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new ProviderError(
        `LLM provider returned ${response.status}: ${text}`,
      );
    }

    let data: unknown;
    try {
      data = await response.json();
    } catch {
      throw new ProviderError("LLM provider returned invalid JSON");
    }

    return this.extractOutput(data);
  }

  private extractOutput(data: unknown): TurnOutput {
    if (!data || typeof data !== "object") {
      throw new ProviderError("Malformed LLM response: unexpected structure");
    }

    const choices = (data as { choices?: unknown[] }).choices;
    if (!Array.isArray(choices) || choices.length === 0) {
      throw new ProviderError("Malformed LLM response: no choices");
    }

    const firstChoice = choices[0] as { message?: { content?: string } };
    const content = firstChoice?.message?.content;

    if (typeof content !== "string" || content.length === 0) {
      throw new ProviderError("Malformed LLM response: no message content");
    }

    let parsed: { assistantMessage?: unknown; proposal?: unknown };
    try {
      parsed = JSON.parse(content);
    } catch {
      throw new ProviderError("Malformed LLM response: content is not valid JSON");
    }

    if (
      typeof parsed.assistantMessage !== "string" ||
      parsed.assistantMessage.length === 0
    ) {
      throw new ProviderError(
        "Malformed LLM response: missing or empty assistantMessage",
      );
    }

    const result: TurnOutput = {
      assistantMessage: parsed.assistantMessage,
    };

    if (parsed.proposal !== undefined && parsed.proposal !== null) {
      result.proposal = parsed.proposal;
    }

    return result;
  }
}

export function createQwenProviderConfig(): QwenProviderConfig {
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
