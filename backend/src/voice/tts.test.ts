import { describe, it, expect, vi } from "vitest";
import {
  QwenTtsSynthesizer,
  extractPcmFromWav,
  type QwenTtsConfig,
  type TtsHttpClient,
} from "./tts.js";
import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";

/** Build a WAV container around raw PCM with configurable header fields. */
function wav(
  pcm: number[],
  opts: { channels?: number; sampleRate?: number; bits?: number; format?: number } = {},
): Buffer {
  const { channels = 1, sampleRate = 24000, bits = 16, format = 1 } = opts;
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(format, 20);
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE((sampleRate * channels * bits) / 8, 28);
  header.writeUInt16LE((channels * bits) / 8, 32);
  header.writeUInt16LE(bits, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, Buffer.from(pcm)]);
}

/** Scripted HTTP double. Never touches the network. */
class FakeHttp implements TtsHttpClient {
  postCalls: { url: string; headers: Record<string, string>; body: unknown }[] = [];
  getCalls: string[] = [];
  postHandler: (body: unknown) => { status: number; json: unknown } = () => ({
    status: 200,
    json: { output: { audio: { url: "https://cdn.invalid/a.wav" } } },
  });
  getHandler: (url: string) => Buffer = () => wav([1, 2, 3, 4]);

  async postJson(
    url: string,
    headers: Record<string, string>,
    body: unknown,
  ): Promise<{ status: number; json: unknown }> {
    this.postCalls.push({ url, headers, body });
    return this.postHandler(body);
  }

  async getBytes(url: string): Promise<Buffer> {
    this.getCalls.push(url);
    return this.getHandler(url);
  }
}

function config(): QwenTtsConfig {
  return {
    apiKey: "test-key",
    baseUrl: "https://token-plan.example.invalid",
    modelId: "qwen-audio-3.0-tts-plus",
    voiceId: "longanhuan_v3.6",
    timeoutMs: 2000,
    maxAudioBytes: 1024 * 1024,
  };
}

describe("QwenTtsSynthesizer (Token Plan REST)", () => {
  it("posts to the Token Plan SpeechSynthesizer endpoint with key, model, voice, rate", async () => {
    const http = new FakeHttp();
    const tts = new QwenTtsSynthesizer(config(), http);

    const result = await tts.synthesize("Hello there.");

    expect(result.pcm).toEqual(Buffer.from([1, 2, 3, 4]));
    expect(http.postCalls).toHaveLength(1);
    expect(http.postCalls[0].url).toBe(
      "https://token-plan.example.invalid/api/v1/services/audio/tts/SpeechSynthesizer",
    );
    expect(http.postCalls[0].headers["Authorization"]).toBe("Bearer test-key");
    expect(http.postCalls[0].body).toEqual({
      model: "qwen-audio-3.0-tts-plus",
      input: {
        text: "Hello there.",
        voice: "longanhuan_v3.6",
        format: "wav",
        sample_rate: 24000,
      },
    });
    // Audio file is downloaded server-side from output.audio.url.
    expect(http.getCalls).toEqual(["https://cdn.invalid/a.wav"]);
  });

  it("rejects empty text without any HTTP call", async () => {
    const http = new FakeHttp();
    const tts = new QwenTtsSynthesizer(config(), http);
    await expect(tts.synthesize("   ")).rejects.toBeInstanceOf(ProviderError);
    expect(http.postCalls).toHaveLength(0);
    expect(http.getCalls).toHaveLength(0);
  });

  it("maps upstream TTS failure to ProviderError with status", async () => {
    const http = new FakeHttp();
    http.postHandler = () => ({
      status: 400,
      json: { code: "InvalidParameter", message: "bad voice" },
    });
    const tts = new QwenTtsSynthesizer(config(), http);
    const err = await tts.synthesize("Hi").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ProviderError);
    expect((err as Error).message).toContain("400");
    expect((err as Error).message).toContain("bad voice");
    expect(http.getCalls).toHaveLength(0);
  });

  it("rejects a response missing the audio URL", async () => {
    const http = new FakeHttp();
    http.postHandler = () => ({ status: 200, json: { output: {} } });
    const tts = new QwenTtsSynthesizer(config(), http);
    await expect(tts.synthesize("Hi")).rejects.toBeInstanceOf(ProviderError);
    expect(http.getCalls).toHaveLength(0);
  });

  it("rejects a malformed audio URL", async () => {
    const http = new FakeHttp();
    http.postHandler = () => ({
      status: 200,
      json: { output: { audio: { url: "not-a-url" } } },
    });
    const tts = new QwenTtsSynthesizer(config(), http);
    await expect(tts.synthesize("Hi")).rejects.toBeInstanceOf(ProviderError);
    expect(http.getCalls).toHaveLength(0);
  });

  it("maps download failure to ProviderError", async () => {
    const http = new FakeHttp();
    http.getHandler = () => {
      throw new ProviderError("Qwen TTS download returned 403");
    };
    const tts = new QwenTtsSynthesizer(config(), http);
    await expect(tts.synthesize("Hi")).rejects.toBeInstanceOf(ProviderError);
  });

  it("rejects empty downloaded audio", async () => {
    const http = new FakeHttp();
    http.getHandler = () => Buffer.alloc(0);
    const tts = new QwenTtsSynthesizer(config(), http);
    await expect(tts.synthesize("Hi")).rejects.toBeInstanceOf(ProviderError);
  });

  it("rejects a non-WAV container instead of mislabeling bytes", async () => {
    const http = new FakeHttp();
    http.getHandler = () => Buffer.from([1, 2, 3, 4]);
    const tts = new QwenTtsSynthesizer(config(), http);
    await expect(tts.synthesize("Hi")).rejects.toBeInstanceOf(ProviderError);
  });

  it("rejects WAV with empty data chunk", async () => {
    const http = new FakeHttp();
    http.getHandler = () => wav([]);
    const tts = new QwenTtsSynthesizer(config(), http);
    await expect(tts.synthesize("Hi")).rejects.toBeInstanceOf(ProviderError);
  });
});

describe("extractPcmFromWav", () => {
  it("returns the raw PCM payload of a valid 24 kHz mono 16-bit WAV", () => {
    expect(extractPcmFromWav(wav([7, 8, 9, 10]))).toEqual(Buffer.from([7, 8, 9, 10]));
  });

  it("rejects sample-rate mismatch", () => {
    expect(() => extractPcmFromWav(wav([1], { sampleRate: 16000 }))).toThrow(
      ProviderError,
    );
  });

  it("rejects channel mismatch", () => {
    expect(() =>
      extractPcmFromWav(wav([1, 2], { channels: 2 })),
    ).toThrow(ProviderError);
  });

  it("rejects bit-depth mismatch", () => {
    expect(() => extractPcmFromWav(wav([1], { bits: 8 }))).toThrow(
      ProviderError,
    );
  });

  it("rejects non-PCM encoding", () => {
    expect(() => extractPcmFromWav(wav([1], { format: 3 }))).toThrow(
      ProviderError,
    );
  });

  it("rejects truncated containers", () => {
    expect(() => extractPcmFromWav(Buffer.from([1, 2]))).toThrow(ProviderError);
  });

  it("accepts a placeholder data size larger than the payload", () => {
    const file = wav([7, 8, 9, 10]);
    file.writeUInt32LE(0x7fffffff, 40); // streamed-encoding placeholder
    expect(extractPcmFromWav(file)).toEqual(Buffer.from([7, 8, 9, 10]));
  });

  it("honors a declared data size smaller than the payload", () => {
    const file = wav([7, 8, 9, 10]);
    file.writeUInt32LE(2, 40);
    expect(extractPcmFromWav(file)).toEqual(Buffer.from([7, 8]));
  });
});

describe("TTS timeout behavior", () => {
  it("maps a hung synthesis request to ProviderTimeoutError", async () => {
    const hanging: TtsHttpClient = {
      postJson: () => new Promise(() => undefined),
      getBytes: () => {
        throw new Error("must not download");
      },
    };
    const tts = new QwenTtsSynthesizer({ ...config(), timeoutMs: 30 }, hanging);
    await expect(tts.synthesize("Hi")).rejects.toBeInstanceOf(
      ProviderTimeoutError,
    );
    vi.restoreAllMocks();
  });
});
