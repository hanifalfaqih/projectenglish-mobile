import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { QwenProvider } from "./qwen.js";
import { ProviderError, ProviderTimeoutError } from "../service.js";
import type { TurnInput } from "./index.js";

const baseConfig = {
  apiKey: "test-api-key",
  baseUrl: "https://test.example.com/v1",
  modelId: "qwen3.7-flash",
  timeoutMs: 5000,
};

const baseInput: TurnInput = {
  systemPrompt: "You are an interviewer.",
  state: {
    phase: "experience",
    topics: [{ id: "t1", label: "mobile app", covered: false }],
    currentTopicId: "t1",
    questionCount: 2,
  },
  messages: [
    { role: "user", content: "I built a mobile app." },
  ],
};

function mockFetchResponse(body: unknown, status = 200) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  });
}

describe("QwenProvider", () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it("sends POST to the correct endpoint", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hello" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    expect(fetchMock).toHaveBeenCalledWith(
      "https://test.example.com/v1/chat/completions",
      expect.any(Object),
    );
  });

  it("uses POST method", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const callArgs = fetchMock.mock.calls[0][1];
    expect(callArgs.method).toBe("POST");
  });

  it("sends Authorization header with Bearer token", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const callArgs = fetchMock.mock.calls[0][1];
    expect(callArgs.headers.Authorization).toBe("Bearer test-api-key");
  });

  it("sends the correct model identifier", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.model).toBe("qwen3.7-flash");
  });

  it("sends json_schema response_format with strict: true", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.response_format.type).toBe("json_schema");
    expect(body.response_format.json_schema.strict).toBe(true);
    expect(body.response_format.json_schema.name).toBe("turn_output");
    expect(body.response_format.json_schema.schema).toBeDefined();
  });

  it("sends enable_thinking: false", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.enable_thinking).toBe(false);
  });

  it("does NOT send max_tokens", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.max_tokens).toBeUndefined();
    expect(body.max_completion_tokens).toBeUndefined();
  });

  it("includes system prompt as first message", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.messages[0].role).toBe("system");
    expect(body.messages[0].content).toBe("You are an interviewer.");
  });

  it("includes transcript messages after system prompt", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body.messages).toHaveLength(2);
    expect(body.messages[1].role).toBe("user");
    expect(body.messages[1].content).toBe("I built a mobile app.");
  });

  it("extracts assistantMessage and proposal from structured response", async () => {
    const modelOutput = {
      assistantMessage: "Tell me more about your mobile app.",
      proposal: {
        phase: "deep_dive",
        newTopics: [{ label: "React Native" }],
      },
    };
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify(modelOutput) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    const result = await provider.generateTurn(baseInput);

    expect(result.assistantMessage).toBe("Tell me more about your mobile app.");
    expect(result.proposal).toEqual(modelOutput.proposal);
  });

  it("handles response with null proposal", async () => {
    const modelOutput = {
      assistantMessage: "That's interesting.",
      proposal: null,
    };
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify(modelOutput) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    const result = await provider.generateTurn(baseInput);

    expect(result.assistantMessage).toBe("That's interesting.");
    expect(result.proposal).toBeUndefined();
  });

  it("handles response without proposal field", async () => {
    const modelOutput = { assistantMessage: "OK." };
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify(modelOutput) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    const result = await provider.generateTurn(baseInput);

    expect(result.assistantMessage).toBe("OK.");
    expect(result.proposal).toBeUndefined();
  });

  it("throws ProviderError on non-OK HTTP response", async () => {
    const fetchMock = mockFetchResponse({ error: "bad request" }, 400);
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await expect(provider.generateTurn(baseInput)).rejects.toThrow(ProviderError);
  });

  it("throws ProviderError on malformed response (no choices)", async () => {
    const fetchMock = mockFetchResponse({});
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await expect(provider.generateTurn(baseInput)).rejects.toThrow(ProviderError);
  });

  it("throws ProviderError on invalid JSON content", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: "not json" } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await expect(provider.generateTurn(baseInput)).rejects.toThrow(ProviderError);
  });

  it("throws ProviderError when assistantMessage is missing", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ proposal: {} }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await expect(provider.generateTurn(baseInput)).rejects.toThrow(ProviderError);
  });

  it("throws ProviderTimeoutError on abort", async () => {
    globalThis.fetch = vi.fn().mockImplementation((_url: string, opts: RequestInit) => {
      return new Promise((_resolve, reject) => {
        if (opts.signal) {
          opts.signal.addEventListener("abort", () => {
            const err = new DOMException("The operation was aborted", "AbortError");
            reject(err);
          });
        }
      });
    });

    const provider = new QwenProvider({ ...baseConfig, timeoutMs: 50 });
    await expect(provider.generateTurn(baseInput)).rejects.toThrow(
      ProviderTimeoutError,
    );
  });

  it("throws ProviderError on network failure", async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error("Network error"));

    const provider = new QwenProvider(baseConfig);
    await expect(provider.generateTurn(baseInput)).rejects.toThrow(ProviderError);
  });

  it("does not retry on failure", async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error("fail"));
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await expect(provider.generateTurn(baseInput)).rejects.toThrow();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("passes AbortSignal to fetch for timeout", async () => {
    const fetchMock = mockFetchResponse({
      choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi" }) } }],
    });
    globalThis.fetch = fetchMock;

    const provider = new QwenProvider(baseConfig);
    await provider.generateTurn(baseInput);

    const callArgs = fetchMock.mock.calls[0][1];
    expect(callArgs.signal).toBeDefined();
  });

  describe("JSON Schema strict:true conformance", () => {
    function getSentSchema(): Record<string, unknown> {
      const fetchMock = mockFetchResponse({
        choices: [{ message: { content: JSON.stringify({ assistantMessage: "Hi", proposal: null }) } }],
      });
      globalThis.fetch = fetchMock;
      const provider = new QwenProvider(baseConfig);
      return provider.generateTurn(baseInput).then(() => {
        const body = JSON.parse(fetchMock.mock.calls[0][1].body);
        return body.response_format.json_schema.schema;
      }) as unknown as Record<string, unknown>;
    }

    it("top-level required includes both assistantMessage and proposal", async () => {
      const schema = await getSentSchema();
      expect(schema.required).toEqual(
        expect.arrayContaining(["assistantMessage", "proposal"]),
      );
    });

    it("top-level additionalProperties is false", async () => {
      const schema = await getSentSchema();
      expect(schema.additionalProperties).toBe(false);
    });

    it("proposal object has all five fields in required", async () => {
      const schema = await getSentSchema();
      const proposalSchema = (schema.properties as Record<string, { anyOf: Record<string, unknown>[] }>)
        .proposal.anyOf.find((s: Record<string, unknown>) => s.type === "object");
      expect(proposalSchema!.required).toEqual(
        expect.arrayContaining([
          "phase",
          "newTopics",
          "coveredTopicIds",
          "currentTopicId",
          "relabelTopics",
        ]),
      );
    });

    it("proposal object has additionalProperties false", async () => {
      const schema = await getSentSchema();
      const proposalSchema = (schema.properties as Record<string, { anyOf: Record<string, unknown>[] }>)
        .proposal.anyOf.find((s: Record<string, unknown>) => s.type === "object");
      expect(proposalSchema!.additionalProperties).toBe(false);
    });

    it("optional proposal fields use anyOf with null", async () => {
      const schema = await getSentSchema();
      const proposalSchema = (schema.properties as Record<string, { anyOf: Record<string, unknown>[] }>)
        .proposal.anyOf.find((s: Record<string, unknown>) => s.type === "object")!;
      const props = proposalSchema.properties as Record<string, { anyOf?: { type: string }[] }>;

      for (const field of ["phase", "newTopics", "coveredTopicIds", "currentTopicId", "relabelTopics"]) {
        const fieldSchema = props[field];
        expect(fieldSchema.anyOf).toBeDefined();
        expect(
          fieldSchema.anyOf!.some((s) => s.type === "null"),
        ).toBe(true);
      }
    });

    it("newTopics items have required and additionalProperties false", async () => {
      const schema = await getSentSchema();
      const proposalSchema = (schema.properties as Record<string, { anyOf: Record<string, unknown>[] }>)
        .proposal.anyOf.find((s: Record<string, unknown>) => s.type === "object")!;
      const newTopics = (proposalSchema.properties as Record<string, { anyOf: Record<string, unknown>[] }>)
        .newTopics.anyOf.find((s: Record<string, unknown>) => s.type === "array")!;
      const items = newTopics.items as Record<string, unknown>;
      expect(items.required).toEqual(["label"]);
      expect(items.additionalProperties).toBe(false);
    });

    it("relabelTopics items have required and additionalProperties false", async () => {
      const schema = await getSentSchema();
      const proposalSchema = (schema.properties as Record<string, { anyOf: Record<string, unknown>[] }>)
        .proposal.anyOf.find((s: Record<string, unknown>) => s.type === "object")!;
      const relabelTopics = (proposalSchema.properties as Record<string, { anyOf: Record<string, unknown>[] }>)
        .relabelTopics.anyOf.find((s: Record<string, unknown>) => s.type === "array")!;
      const items = relabelTopics.items as Record<string, unknown>;
      expect(items.required).toEqual(["id", "label"]);
      expect(items.additionalProperties).toBe(false);
    });
  });
});
