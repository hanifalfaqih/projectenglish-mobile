import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { QwenAsrProvider, createQwenAsrProviderConfig } from "./provider.js";
import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";

function asrResponse(text: string) {
  return { output: { text } };
}

function mockFetch(body: unknown, status = 200) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  });
}

describe("QwenAsrProvider", () => {
  let originalFetch: typeof globalThis.fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  function provider() {
    return new QwenAsrProvider({
      apiKey: "test-key",
      asrBaseUrl: "https://example.invalid",
      modelId: "qwen-audio-3.0-asr-flash",
      timeoutMs: 5000,
    });
  }

  it("posts WAV audio to the native endpoint and returns the transcript", async () => {
    const fetchMock = mockFetch(asrResponse("hello world"));
    globalThis.fetch = fetchMock;

    const text = await provider().transcribe(new Uint8Array([1, 2, 3]));

    expect(text).toBe("hello world");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(
      "https://example.invalid/api/v1/services/aigc/multimodal-generation/generation",
    );
    const headers = init.headers as Record<string, string>;
    expect(headers["Authorization"]).toBe("Bearer test-key");
    expect(headers["Content-Type"]).toBe("application/json");
    expect(headers["X-DashScope-SSE"]).toBe("disable");
    const body = JSON.parse(init.body as string) as {
      model: string;
      input: {
        messages: {
          content: { type: string; input_audio: { data: string } }[];
        }[];
      };
      parameters: Record<string, unknown>;
    };
    expect(body.model).toBe("qwen-audio-3.0-asr-flash");
    const audio = body.input.messages[0].content[0];
    expect(audio.type).toBe("input_audio");
    expect(audio.input_audio.data.startsWith("data:audio/wav;base64,")).toBe(true);
    // Data URI round-trips to the exact input bytes (PCM→WAV preserved).
    const sent = Buffer.from(
      audio.input_audio.data.slice("data:audio/wav;base64,".length),
      "base64",
    );
    expect(sent).toEqual(Buffer.from([1, 2, 3]));
    // Exact live parameter contract: no extra fields.
    expect(body.parameters).toEqual({
      format: "wav",
      sample_rate: "16000",
    });
    expect(init.method).toBe("POST");
  });

  it("falls back to sentence text when output text is missing", async () => {
    globalThis.fetch = mockFetch({ output: { sentence: { text: "hi there" } } });
    expect(await provider().transcribe(new Uint8Array([1]))).toBe("hi there");
  });

  it("reads the live top-level sentence.text shape", async () => {
    // Exact fixture of the live Token Plan probe (HTTP 200).
    globalThis.fetch = mockFetch({
      sentence: {
        sentence_id: 1,
        begin_time: 160,
        end_time: 2800,
        text: "The weather is nice today. Let us go for a walk. ",
        channel_id: 0,
        speaker_id: null,
        sentence_end: true,
        words: [{ begin_time: 160, end_time: 320, text: "The" }],
      },
    });
    expect(await provider().transcribe(new Uint8Array([1]))).toBe(
      "The weather is nice today. Let us go for a walk.",
    );
  });

  it("prefers output.text over sentence shapes", async () => {
    globalThis.fetch = mockFetch({
      output: { text: "primary", sentence: { text: "secondary" } },
      sentence: { text: "tertiary" },
    });
    expect(await provider().transcribe(new Uint8Array([1]))).toBe("primary");
  });

  it("returns null for blank transcripts", async () => {
    globalThis.fetch = mockFetch(asrResponse("   "));
    expect(await provider().transcribe(new Uint8Array([1]))).toBeNull();
  });

  it("returns null when output is missing", async () => {
    globalThis.fetch = mockFetch({});
    expect(await provider().transcribe(new Uint8Array([1]))).toBeNull();
  });

  it("throws ProviderError with upstream status on non-2xx", async () => {
    globalThis.fetch = mockFetch({ error: { message: "bad key" } }, 401);
    const err = await provider()
      .transcribe(new Uint8Array([1]))
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect((err as Error).message).toContain("401");
  });

  it("maps upstream 5xx to ProviderError", async () => {
    globalThis.fetch = mockFetch({ message: "overloaded" }, 503);
    const err = await provider()
      .transcribe(new Uint8Array([1]))
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect((err as Error).message).toContain("503");
  });

  it("never leaks the API key in error messages", async () => {
    globalThis.fetch = mockFetch({ error: "nope" }, 500);
    const err = (await provider()
      .transcribe(new Uint8Array([1]))
      .catch((e: unknown) => e)) as Error;
    expect(err.message).not.toContain("test-key");
  });

  it("throws ProviderTimeoutError on abort", async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new DOMException("aborted", "AbortError"));
    const err = await provider()
      .transcribe(new Uint8Array([1]))
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ProviderTimeoutError);
  });

  it("throws ProviderError on malformed JSON", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => {
        throw new Error("bad json");
      },
      text: async () => "not json",
    });
    const err = await provider()
      .transcribe(new Uint8Array([1]))
      .catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ProviderError);
  });

  it("defaults config to the Token Plan endpoint and live model", () => {
    const saved = {
      key: process.env.DASHSCOPE_API_KEY,
      base: process.env.ASR_BASE_URL,
      model: process.env.ASR_MODEL_ID,
    };
    process.env.DASHSCOPE_API_KEY = "test-key";
    delete process.env.ASR_BASE_URL;
    delete process.env.ASR_MODEL_ID;
    try {
      const cfg = createQwenAsrProviderConfig();
      expect(cfg.asrBaseUrl).toBe(
        "https://token-plan.ap-southeast-1.maas.aliyuncs.com",
      );
      expect(cfg.modelId).toBe("qwen-audio-3.0-asr-flash");
    } finally {
      if (saved.key === undefined) delete process.env.DASHSCOPE_API_KEY;
      else process.env.DASHSCOPE_API_KEY = saved.key;
      if (saved.base === undefined) delete process.env.ASR_BASE_URL;
      else process.env.ASR_BASE_URL = saved.base;
      if (saved.model === undefined) delete process.env.ASR_MODEL_ID;
      else process.env.ASR_MODEL_ID = saved.model;
    }
  });
});
