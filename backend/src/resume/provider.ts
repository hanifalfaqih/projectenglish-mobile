import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";
import { RESUME_PARSER_PROMPT_VERSION, buildResumeParserPrompt } from "./prompt.js";

/**
 * Resume parser provider boundary — a SEPARATE narrow interface, like the
 * M11/M12/M19 providers. The interview `LLMProvider.generateTurn` contract and
 * all other providers are untouched. Output is untrusted structured JSON; the
 * service validates it against the canonical experience schema before use.
 */
export interface ResumeParseOutputRaw {
  items?: unknown;
  [key: string]: unknown;
}

export interface ResumeParserProvider {
  parseResumeText(resumeText: string): Promise<ResumeParseOutputRaw>;
}

export interface QwenResumeParserConfig {
  apiKey: string;
  baseUrl: string;
  modelId: string;
  timeoutMs: number;
  promptVersion: string;
}

/**
 * Transport-level declaration of the parser output. Mirrors `ExperienceItemInput`
 * (`experience/schema.ts`): required title/description, nullable organization
 * and role, skills array. `additionalProperties: false` keeps the model from
 * naming fields the backend does not accept.
 */
export const RESUME_PARSE_OUTPUT_SCHEMA = {
  type: "object",
  properties: {
    items: {
      type: "array",
      maxItems: 20,
      items: {
        type: "object",
        properties: {
          title: { type: "string" },
          organization: { anyOf: [{ type: "string" }, { type: "null" }] },
          role: { anyOf: [{ type: "string" }, { type: "null" }] },
          description: { type: "string" },
          skills: { type: "array", items: { type: "string" } },
        },
        required: ["title", "organization", "role", "description", "skills"],
        additionalProperties: false,
      },
    },
  },
  required: ["items"],
  additionalProperties: false,
};

export class QwenResumeParserProvider implements ResumeParserProvider {
  constructor(private readonly config: QwenResumeParserConfig) {}

  async parseResumeText(resumeText: string): Promise<ResumeParseOutputRaw> {
    const systemPrompt = buildResumeParserPrompt(resumeText);

    const body = {
      model: this.config.modelId,
      messages: [{ role: "system" as const, content: systemPrompt }],
      enable_thinking: false,
      response_format: {
        type: "json_schema",
        json_schema: {
          name: "resume_parse_output",
          strict: true,
          schema: RESUME_PARSE_OUTPUT_SCHEMA,
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
          `Resume parser provider timed out after ${this.config.timeoutMs}ms`,
          err,
        );
      }
      throw new ProviderError("Resume parser provider request failed", err);
    } finally {
      clearTimeout(timeoutId);
    }

    // Single attempt: no provider retry (matches the interview/feedback posture).
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new ProviderError(
        `Resume parser provider returned ${response.status}: ${text}`,
      );
    }

    let data: unknown;
    try {
      data = await response.json();
    } catch {
      throw new ProviderError("Resume parser provider returned invalid JSON");
    }

    return this.extractOutput(data);
  }

  private extractOutput(data: unknown): ResumeParseOutputRaw {
    if (!data || typeof data !== "object") {
      throw new ProviderError(
        "Malformed resume parser response: unexpected structure",
      );
    }
    const choices = (data as { choices?: unknown[] }).choices;
    if (!Array.isArray(choices) || choices.length === 0) {
      throw new ProviderError("Malformed resume parser response: no choices");
    }
    const content = (choices[0] as { message?: { content?: string } })?.message
      ?.content;
    if (typeof content !== "string" || content.length === 0) {
      throw new ProviderError(
        "Malformed resume parser response: no message content",
      );
    }
    try {
      return JSON.parse(content) as ResumeParseOutputRaw;
    } catch {
      throw new ProviderError(
        "Malformed resume parser response: content is not valid JSON",
      );
    }
  }
}

export function createQwenResumeParserConfig(): QwenResumeParserConfig {
  const apiKey = process.env.DASHSCOPE_API_KEY;
  if (!apiKey) {
    throw new Error("DASHSCOPE_API_KEY environment variable is required");
  }
  return {
    apiKey,
    baseUrl:
      process.env.LLM_BASE_URL ??
      "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
    // Parser-specific override; falls back to the shared model so the
    // production interview/feedback model is never silently changed.
    modelId:
      process.env.LLM_RESUME_MODEL_ID ??
      process.env.LLM_MODEL_ID ??
      "qwen3.8-flash",
    timeoutMs: Number(process.env.LLM_TIMEOUT_MS) || 30000,
    promptVersion: RESUME_PARSER_PROMPT_VERSION,
  };
}
