import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";
import {
  RESUME_PARSE_OUTPUT_SCHEMA,
  QwenResumeParserProvider,
} from "./provider.js";

const baseConfig = {
  apiKey: "test-api-key",
  baseUrl: "https://test.example.com/v1",
  modelId: "test-model",
  timeoutMs: 5000,
  promptVersion: "1.0.0",
};

const resumeText = "Jane Doe. Backend Intern at PT Example. Built REST APIs.";

function mockFetchResponse(body: unknown, status = 200) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  });
}

function modelContent(payload: unknown) {
  return { choices: [{ message: { content: JSON.stringify(payload) } }] };
}

let originalFetch: typeof globalThis.fetch;

beforeEach(() => {
  originalFetch = globalThis.fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("QwenResumeParserProvider — transport contract", () => {
  it("posts to the chat-completions endpoint with the bearer key and model", async () => {
    const fetchMock = mockFetchResponse(modelContent({ items: [] }));
    globalThis.fetch = fetchMock;

    await new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("https://test.example.com/v1/chat/completions");
    expect(init.method).toBe("POST");
    expect(init.headers.Authorization).toBe("Bearer test-api-key");
    expect(init.headers["Content-Type"]).toBe("application/json");
    const body = JSON.parse(init.body as string);
    expect(body.model).toBe("test-model");
    expect(body.enable_thinking).toBe(false);
  });

  it("sends exactly one system message carrying the resume text", async () => {
    const fetchMock = mockFetchResponse(modelContent({ items: [] }));
    globalThis.fetch = fetchMock;

    await new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body as string);
    expect(body.messages).toHaveLength(1);
    expect(body.messages[0].role).toBe("system");
    expect(body.messages[0].content).toContain(resumeText);
  });

  it("requests a strict json_schema response format named resume_parse_output", async () => {
    const fetchMock = mockFetchResponse(modelContent({ items: [] }));
    globalThis.fetch = fetchMock;

    await new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body as string);
    expect(body.response_format.type).toBe("json_schema");
    expect(body.response_format.json_schema.name).toBe("resume_parse_output");
    expect(body.response_format.json_schema.strict).toBe(true);
    expect(body.response_format.json_schema.schema).toEqual(
      RESUME_PARSE_OUTPUT_SCHEMA,
    );
  });

  it("parses the message content as the raw envelope", async () => {
    globalThis.fetch = mockFetchResponse(
      modelContent({
        items: [
          {
            title: "Backend Intern",
            organization: "PT Example",
            role: "Intern",
            description: "Built REST APIs.",
            skills: ["Node.js"],
          },
        ],
      }),
    );
    const raw =
      await new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText);
    expect(raw).toEqual({
      items: [
        {
          title: "Backend Intern",
          organization: "PT Example",
          role: "Intern",
          description: "Built REST APIs.",
          skills: ["Node.js"],
        },
      ],
    });
  });

  it("throws ProviderError when the content is not valid JSON", async () => {
    globalThis.fetch = mockFetchResponse({
      choices: [{ message: { content: "not-json{{{ " } }],
    });
    await expect(
      new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText),
    ).rejects.toThrow(ProviderError);
  });

  it("throws ProviderError when there are no choices", async () => {
    globalThis.fetch = mockFetchResponse({ choices: [] });
    await expect(
      new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText),
    ).rejects.toThrow(ProviderError);
  });

  it("throws ProviderError on a non-2xx provider status", async () => {
    globalThis.fetch = mockFetchResponse({ error: "overloaded" }, 503);
    await expect(
      new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText),
    ).rejects.toThrow(ProviderError);
  });

  it("throws ProviderTimeoutError when the request is aborted", async () => {
    globalThis.fetch = vi
      .fn()
      .mockRejectedValue(new DOMException("aborted", "AbortError"));
    await expect(
      new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText),
    ).rejects.toThrow(ProviderTimeoutError);
  });

  it("throws ProviderError on transport failure", async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error("network down"));
    await expect(
      new QwenResumeParserProvider(baseConfig).parseResumeText(resumeText),
    ).rejects.toThrow(ProviderError);
  });
});

describe("createQwenResumeParserConfig", () => {
  it("prefers LLM_RESUME_MODEL_ID over LLM_MODEL_ID", async () => {
    const { createQwenResumeParserConfig } = await import("./provider.js");
    const prev = {
      key: process.env.DASHSCOPE_API_KEY,
      base: process.env.LLM_BASE_URL,
      model: process.env.LLM_MODEL_ID,
      resumeModel: process.env.LLM_RESUME_MODEL_ID,
      timeout: process.env.LLM_TIMEOUT_MS,
    };
    process.env.DASHSCOPE_API_KEY = "k";
    process.env.LLM_MODEL_ID = "shared-model";
    process.env.LLM_RESUME_MODEL_ID = "resume-model";
    try {
      expect(createQwenResumeParserConfig().modelId).toBe("resume-model");
      delete process.env.LLM_RESUME_MODEL_ID;
      expect(createQwenResumeParserConfig().modelId).toBe("shared-model");
    } finally {
      if (prev.key === undefined) delete process.env.DASHSCOPE_API_KEY;
      else process.env.DASHSCOPE_API_KEY = prev.key;
      if (prev.base === undefined) delete process.env.LLM_BASE_URL;
      else process.env.LLM_BASE_URL = prev.base;
      if (prev.model === undefined) delete process.env.LLM_MODEL_ID;
      else process.env.LLM_MODEL_ID = prev.model;
      if (prev.resumeModel === undefined) delete process.env.LLM_RESUME_MODEL_ID;
      else process.env.LLM_RESUME_MODEL_ID = prev.resumeModel;
      if (prev.timeout === undefined) delete process.env.LLM_TIMEOUT_MS;
      else process.env.LLM_TIMEOUT_MS = prev.timeout;
    }
  });
});
