import { describe, it, expect, vi, beforeEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import multipart from "@fastify/multipart";
import { registerTranscriptionRoutes, registerVoiceRoutes } from "./routes.js";
import {
  VoiceNoSpeechError,
  VoiceAudioTooLargeError,
  VoiceAudioInvalidError,
  VoiceTurnService,
  type TranscribeResult,
  type VoiceTurnResult,
} from "./service.js";
import {
  ConversationClosedError,
  ConversationNotFoundError,
  OpeningAlreadyTakenError,
  ProviderError,
  ProviderTimeoutError,
  TurnInProgressError,
  type TurnResult,
} from "../conversation/service.js";

const BOUNDARY = "--------------------------voicetestboundary";

function multipartBody(parts: { name: string; value?: string; file?: Buffer; filename?: string }[]) {
  const chunks: Buffer[] = [];
  for (const part of parts) {
    if (part.file) {
      chunks.push(
        Buffer.from(
          `--${BOUNDARY}\r\nContent-Disposition: form-data; name="${part.name}"; filename="${part.filename ?? "answer.pcm"}"\r\nContent-Type: application/octet-stream\r\n\r\n`,
          "utf8",
        ),
      );
      chunks.push(part.file);
      chunks.push(Buffer.from("\r\n", "utf8"));
    } else {
      chunks.push(
        Buffer.from(
          `--${BOUNDARY}\r\nContent-Disposition: form-data; name="${part.name}"\r\n\r\n${part.value ?? ""}\r\n`,
          "utf8",
        ),
      );
    }
  }
  chunks.push(Buffer.from(`--${BOUNDARY}--\r\n`, "utf8"));
  return Buffer.concat(chunks);
}

const turnResult: TurnResult = {
  assistantMessage: "Tell me more.",
  state: { phase: "experience", topics: [], currentTopicId: null, questionCount: 1 },
  status: "active",
  closing: false,
};

function buildApp(service: VoiceTurnService): FastifyInstance {
  const app = Fastify();
  void app.register(multipart);
  registerVoiceRoutes(app, { service });
  return app;
}

function buildService(
  voiceTurn: (input: {
    conversationId: string;
    clientTurnId: string;
    pcm: Uint8Array;
  }) => Promise<VoiceTurnResult>,
): VoiceTurnService {
  return { voiceTurn: vi.fn(voiceTurn) } as unknown as VoiceTurnService;
}

const voiceOk: VoiceTurnResult = {
  transcript: "spoken",
  turn: turnResult,
  audio: Buffer.from([5, 6]),
  audioError: undefined,
  timings: {
    receivedMs: 1,
    parsedMs: 2,
    asrStartMs: 2,
    asrDoneMs: 3,
    llmStartMs: 3,
    llmDoneMs: 4,
    ttsStartMs: 4,
    ttsDoneMs: 5,
    inputBytes: 4,
    audioBytes: 2,
  },
};

describe("POST /conversations/:id/voice-turn", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function post(app: FastifyInstance, body: Buffer) {
    return app.inject({
      method: "POST",
      url: "/conversations/conv-1/voice-turn",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: body,
    });
  }

  it("returns 200 with transcript and turn result", async () => {
    const service = buildService(async () => voiceOk);
    const app = buildApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([
        { name: "clientTurnId", value: "turn-1" },
        { name: "audio", file: Buffer.from([1, 2, 3, 4]) },
      ]),
    );

    expect(res.statusCode).toBe(200);
    const body = res.json() as Record<string, unknown>;
    expect(body["transcript"]).toBe("spoken");
    expect(body["assistantMessage"]).toBe("Tell me more.");
    expect(body["status"]).toBe("active");
    expect(body["closing"]).toBe(false);
    expect(service.voiceTurn).toHaveBeenCalledWith({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      pcm: expect.any(Uint8Array),
    });
  });

  it("returns base64 PCM audio with format metadata", async () => {
    const service = buildService(async () => voiceOk);
    const app = buildApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([
        { name: "clientTurnId", value: "turn-1" },
        { name: "audio", file: Buffer.from([1, 2, 3, 4]) },
      ]),
    );

    expect(res.statusCode).toBe(200);
    const body = res.json() as {
      audio: { format: string; sampleRateHz: number; channels: number; data: string } | null;
    };
    expect(body.audio?.format).toBe("pcm");
    expect(body.audio?.sampleRateHz).toBe(24000);
    expect(body.audio?.channels).toBe(1);
    expect(Buffer.from(body.audio?.data ?? "", "base64")).toEqual(Buffer.from([5, 6]));
  });

  it("returns null audio with audioError when synthesis failed", async () => {
    const service = buildService(async () => ({
      ...voiceOk,
      audio: null,
      audioError: "Voice output unavailable, showing text.",
    }));
    const app = buildApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([
        { name: "clientTurnId", value: "turn-1" },
        { name: "audio", file: Buffer.from([1, 2, 3, 4]) },
      ]),
    );

    // Text outcome intact despite TTS failure.
    expect(res.statusCode).toBe(200);
    const body = res.json() as Record<string, unknown>;
    expect(body["audio"]).toBeNull();
    expect(body["audioError"]).toBe("Voice output unavailable, showing text.");
    expect(body["assistantMessage"]).toBe("Tell me more.");
  });

  it("returns 400 when audio is missing", async () => {
    const service = buildService(async () => {
      throw new Error("must not be called");
    });
    const app = buildApp(service);
    await app.ready();

    const res = await post(app, multipartBody([{ name: "clientTurnId", value: "t" }]));
    expect(res.statusCode).toBe(400);
    expect(service.voiceTurn).not.toHaveBeenCalled();
  });

  it("returns 400 for blank clientTurnId", async () => {
    const service = buildService(async () => {
      throw new Error("must not be called");
    });
    const app = buildApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([
        { name: "clientTurnId", value: "" },
        { name: "audio", file: Buffer.from([1]) },
      ]),
    );
    expect(res.statusCode).toBe(400);
  });

  it("returns 404 for unknown conversation", async () => {
    const service = buildService(async () => {
      throw new ConversationNotFoundError("conv-1");
    });
    const app = buildApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([
        { name: "clientTurnId", value: "t" },
        { name: "audio", file: Buffer.from([1]) },
      ]),
    );
    expect(res.statusCode).toBe(404);
  });

  it("returns 422 with no_speech code for blank transcripts", async () => {
    const service = buildService(async () => {
      throw new VoiceNoSpeechError();
    });
    const app = buildApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([
        { name: "clientTurnId", value: "t" },
        { name: "audio", file: Buffer.from([0, 0]) },
      ]),
    );
    expect(res.statusCode).toBe(422);
    expect((res.json() as Record<string, unknown>)["code"]).toBe("no_speech");
  });

  it("returns 409 for closed and in-progress conversations", async () => {
    for (const err of [new ConversationClosedError("c"), new TurnInProgressError("c")]) {
      const service = buildService(async () => {
        throw err;
      });
      const app = buildApp(service);
      await app.ready();
      const res = await post(
        app,
        multipartBody([
          { name: "clientTurnId", value: "t" },
          { name: "audio", file: Buffer.from([1]) },
        ]),
      );
      expect(res.statusCode).toBe(409);
      await app.close();
    }
  });

  it("returns 502 and 504 for provider failures", async () => {
    const cases: [unknown, number][] = [
      [new ProviderError("down"), 502],
      [new ProviderTimeoutError("slow"), 504],
    ];
    for (const [err, status] of cases) {
      const service = buildService(async () => {
        throw err;
      });
      const app = buildApp(service);
      await app.ready();
      const res = await post(
        app,
        multipartBody([
          { name: "clientTurnId", value: "t" },
          { name: "audio", file: Buffer.from([1]) },
        ]),
      );
      expect(res.statusCode).toBe(status);
      await app.close();
    }
  });

  it("returns 413 for oversized audio", async () => {
    const service = buildService(async () => {
      throw new VoiceAudioTooLargeError(9999999);
    });
    const app = buildApp(service);
    await app.ready();
    const res = await post(
      app,
      multipartBody([
        { name: "clientTurnId", value: "t" },
        { name: "audio", file: Buffer.from([1]) },
      ]),
    );
    expect(res.statusCode).toBe(413);
  });
});

describe("POST /conversations/:id/opening", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function buildOpeningService(
    opening: () => Promise<{ assistantMessage: string; audio: Buffer | null; audioError?: string }>,
  ): VoiceTurnService {
    return { openingTurn: vi.fn(opening) } as unknown as VoiceTurnService;
  }

  it("returns 200 with the opening message and PCM audio", async () => {
    const service = buildOpeningService(async () => ({
      assistantMessage: "Welcome in.",
      audio: Buffer.from([5, 6]),
      audioError: undefined,
    }));
    const app = buildApp(service);
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/conv-1/opening" });
    expect(res.statusCode).toBe(200);
    const body = res.json() as Record<string, unknown>;
    expect(body["assistantMessage"]).toBe("Welcome in.");
    expect(body["audio"]).toMatchObject({
      format: "pcm",
      sampleRateHz: 24000,
      channels: 1,
    });
    expect(service.openingTurn).toHaveBeenCalledWith("conv-1");
    await app.close();
  });

  it("returns null audio with audioError when TTS failed", async () => {
    const service = buildOpeningService(async () => ({
      assistantMessage: "Welcome in.",
      audio: null,
      audioError: "Voice output unavailable, showing text.",
    }));
    const app = buildApp(service);
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/conv-1/opening" });
    expect(res.statusCode).toBe(200);
    const body = res.json() as Record<string, unknown>;
    expect(body["audio"]).toBeNull();
    expect(body["audioError"]).toBe("Voice output unavailable, showing text.");
    await app.close();
  });

  it("maps opening errors to status codes", async () => {
    const cases: [unknown, number][] = [
      [new ConversationNotFoundError("conv-1"), 404],
      [new OpeningAlreadyTakenError("conv-1"), 409],
      [new ProviderTimeoutError("slow"), 504],
      [new ProviderError("down"), 502],
    ];
    for (const [error, status] of cases) {
      const service = buildOpeningService(async () => {
        throw error;
      });
      const app = buildApp(service);
      await app.ready();
      const res = await app.inject({ method: "POST", url: "/conversations/conv-1/opening" });
      expect(res.statusCode).toBe(status);
      await app.close();
    }
  });
});

describe("POST /conversations/:id/voice-turn timing hygiene", () => {
  function postTiming(app: FastifyInstance, body: Buffer) {
    return app.inject({
      method: "POST",
      url: "/conversations/conv-1/voice-turn",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: body,
    });
  }

  it("never serializes internal timings into the response", async () => {
    const service = buildService(async () => voiceOk);
    const app = buildApp(service);
    await app.ready();
    const res = await postTiming(
      app,
      multipartBody([
        { name: "clientTurnId", value: "turn-1" },
        { name: "audio", file: Buffer.from([1, 2, 3, 4]) },
      ]),
    );
    expect(res.statusCode).toBe(200);
    expect(res.json()).not.toHaveProperty("timings");
    await app.close();
  });
});

/** Builds a service double exposing ONLY transcribeOnly, so an accidental
 * turn/TTS call in the transcription path fails loudly. */
function buildTranscribeService(
  transcribeOnly: (input: { pcm: Uint8Array }) => Promise<TranscribeResult>,
): VoiceTurnService {
  return { transcribeOnly: vi.fn(transcribeOnly) } as unknown as VoiceTurnService;
}

function buildTranscribeApp(service: VoiceTurnService): FastifyInstance {
  const app = Fastify();
  void app.register(multipart);
  registerTranscriptionRoutes(app, { service });
  return app;
}

describe("POST /transcription", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function post(app: FastifyInstance, body: Buffer) {
    return app.inject({
      method: "POST",
      url: "/transcription",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: body,
    });
  }

  it("returns 200 with only the transcript", async () => {
    const service = buildTranscribeService(async () => ({
      transcript: "I led the Android migration.",
    }));
    const app = buildTranscribeApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([{ name: "audio", file: Buffer.from([1, 2, 3, 4]) }]),
    );

    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ transcript: "I led the Android migration." });
    await app.close();
  });

  it("passes the uploaded audio bytes through untouched", async () => {
    const pcm = Buffer.from([9, 8, 7, 6]);
    let seen: Uint8Array | null = null;
    const service = buildTranscribeService(async (input) => {
      seen = input.pcm;
      return { transcript: "ok" };
    });
    const app = buildTranscribeApp(service);
    await app.ready();

    const res = await post(app, multipartBody([{ name: "audio", file: pcm }]));

    expect(res.statusCode).toBe(200);
    expect(Buffer.from(seen as unknown as Uint8Array)).toEqual(pcm);
    await app.close();
  });

  it("never creates a conversation turn and needs no conversation id", async () => {
    // The URL carries no :id at all, and the service double exposes only
    // transcribeOnly — a voiceTurn call would throw.
    const service = buildTranscribeService(async () => ({ transcript: "ok" }));
    const app = buildTranscribeApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([{ name: "audio", file: Buffer.from([1]) }]),
    );

    expect(res.statusCode).toBe(200);
    expect(service.transcribeOnly).toHaveBeenCalledOnce();
    await app.close();
  });

  it("returns 400 when audio is missing", async () => {
    const service = buildTranscribeService(async () => ({ transcript: "ok" }));
    const app = buildTranscribeApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([{ name: "notAudio", value: "x" }]),
    );

    expect(res.statusCode).toBe(400);
    expect(service.transcribeOnly).not.toHaveBeenCalled();
    await app.close();
  });

  it.each([
    [new VoiceNoSpeechError(), 422],
    [new VoiceAudioInvalidError("Empty voice audio"), 400],
    [new VoiceAudioTooLargeError(999), 413],
    [new ProviderTimeoutError("slow"), 504],
    [new ProviderError("down"), 502],
    [new Error("boom"), 500],
  ])("maps %s to the documented status", async (error, status) => {
    const service = buildTranscribeService(async () => {
      throw error;
    });
    const app = buildTranscribeApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([{ name: "audio", file: Buffer.from([1]) }]),
    );

    expect(res.statusCode).toBe(status);
    await app.close();
  });

  it("returns the no_speech code on 422 like voice-turn", async () => {
    const service = buildTranscribeService(async () => {
      throw new VoiceNoSpeechError();
    });
    const app = buildTranscribeApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([{ name: "audio", file: Buffer.from([1]) }]),
    );

    expect(res.statusCode).toBe(422);
    expect(res.json()).toEqual({ error: "No speech detected", code: "no_speech" });
    await app.close();
  });

  it("does not leak server timings or internals", async () => {
    const service = buildTranscribeService(async () => ({ transcript: "ok" }));
    const app = buildTranscribeApp(service);
    await app.ready();

    const res = await post(
      app,
      multipartBody([{ name: "audio", file: Buffer.from([1]) }]),
    );

    const body = res.json() as Record<string, unknown>;
    expect(Object.keys(body)).toEqual(["transcript"]);
    await app.close();
  });
});
