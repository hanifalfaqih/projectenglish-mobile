import { describe, it, expect, vi } from "vitest";
import {
  ConversationClosedError,
  ConversationNotFoundError,
  ConversationService,
  ProviderError,
  ProviderTimeoutError,
  type TurnResult,
} from "../conversation/service.js";
import type { ConversationRepository } from "../conversation/repository.js";
import type { QwenAsrProvider } from "./provider.js";
import {
  VoiceAudioInvalidError,
  VoiceAudioTooLargeError,
  VoiceNoSpeechError,
  VoiceTurnService,
} from "./service.js";

const turnResult: TurnResult = {
  assistantMessage: "Tell me more.",
  state: { phase: "experience", topics: [], currentTopicId: null, questionCount: 1 },
  status: "active",
  closing: false,
};

function setup(overrides: {
  conversation?: unknown;
  hasConversation?: boolean;
  transcript?: string | null;
  processTurn?: TurnResult;
  ttsAudio?: Uint8Array | null;
  ttsError?: unknown;
} = {}) {
  const repository = {
    findById: vi.fn().mockResolvedValue(
      overrides.hasConversation === false ? null : (overrides.conversation ?? { id: "conv-1" }),
    ),
  } as unknown as ConversationRepository;
  const conversationService = {
    processTurn: vi.fn().mockResolvedValue(overrides.processTurn ?? turnResult),
  } as unknown as ConversationService;
  const asrProvider = {
    transcribe: vi.fn().mockResolvedValue(
      overrides.transcript !== undefined ? overrides.transcript : "spoken answer",
    ),
  } as unknown as QwenAsrProvider;
  const tts = {
    synthesize: overrides.ttsError
      ? vi.fn().mockRejectedValue(overrides.ttsError)
      : vi.fn().mockResolvedValue({
          pcm: overrides.ttsAudio !== undefined ? overrides.ttsAudio : Buffer.from([9, 9]),
        }),
  };
  const service = new VoiceTurnService({
    conversationRepository: repository,
    conversationService,
    asrProvider,
    tts: tts as never,
    limits: { maxAudioBytes: 1024 },
  });
  return { repository, conversationService, asrProvider, tts, service };
}

describe("VoiceTurnService", () => {
  it("transcribes then delegates to processTurn with the same clientTurnId", async () => {
    const { service, conversationService, asrProvider } = setup();
    const pcm = new Uint8Array([1, 2, 3, 4]);

    const result = await service.voiceTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-9",
      pcm,
    });

    expect(result.transcript).toBe("spoken answer");
    expect(result.turn).toEqual(turnResult);
    expect(asrProvider.transcribe).toHaveBeenCalledOnce();
    expect(conversationService.processTurn).toHaveBeenCalledWith({
      conversationId: "conv-1",
      clientTurnId: "turn-9",
      message: "spoken answer",
    });
  });

  it("rejects empty audio without calling ASR", async () => {
    const { service, asrProvider } = setup();
    await expect(
      service.voiceTurn({ conversationId: "c", clientTurnId: "t", pcm: new Uint8Array([]) }),
    ).rejects.toBeInstanceOf(VoiceAudioInvalidError);
    expect(asrProvider.transcribe).not.toHaveBeenCalled();
  });

  it("rejects oversized audio without calling ASR", async () => {
    const { service, asrProvider } = setup();
    await expect(
      service.voiceTurn({ conversationId: "c", clientTurnId: "t", pcm: new Uint8Array(2048) }),
    ).rejects.toBeInstanceOf(VoiceAudioTooLargeError);
    expect(asrProvider.transcribe).not.toHaveBeenCalled();
  });

  it("throws not-found without calling ASR", async () => {
    const { service, asrProvider } = setup({ hasConversation: false });
    await expect(
      service.voiceTurn({ conversationId: "missing", clientTurnId: "t", pcm: new Uint8Array([1]) }),
    ).rejects.toBeInstanceOf(ConversationNotFoundError);
    expect(asrProvider.transcribe).not.toHaveBeenCalled();
  });

  it("throws no-speech for blank transcripts without creating a turn", async () => {
    const { service, conversationService } = setup({ transcript: "   " });
    await expect(
      service.voiceTurn({ conversationId: "c", clientTurnId: "t", pcm: new Uint8Array([1]) }),
    ).rejects.toBeInstanceOf(VoiceNoSpeechError);
    expect(conversationService.processTurn).not.toHaveBeenCalled();
  });

  it("propagates closed, provider error, and timeout from the shared path", async () => {
    for (const err of [
      new ConversationClosedError("c"),
      new ProviderError("down"),
      new ProviderTimeoutError("slow"),
    ]) {
      const repository = {
        findById: vi.fn().mockResolvedValue({ id: "c" }),
      } as unknown as ConversationRepository;
      const conversationService = {
        processTurn: vi.fn().mockRejectedValue(err),
      } as unknown as ConversationService;
      const service = new VoiceTurnService({
        conversationRepository: repository,
        conversationService,
        asrProvider: {
          transcribe: vi.fn().mockResolvedValue("hi"),
        } as unknown as QwenAsrProvider,
        tts: {
          synthesize: vi.fn().mockResolvedValue({ pcm: Buffer.from([1]) }),
        } as never,
      });
      await expect(
        service.voiceTurn({ conversationId: "c", clientTurnId: "t", pcm: new Uint8Array([1]) }),
      ).rejects.toBe(err);
    }
  });

  it("attaches synthesized audio after the committed turn", async () => {
    const { service, tts } = setup({ ttsAudio: Buffer.from([7, 8, 9]) });
    const result = await service.voiceTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-9",
      pcm: new Uint8Array([1, 2, 3, 4]),
    });
    expect(tts.synthesize).toHaveBeenCalledWith("Tell me more.");
    expect(result.audio).toEqual(Buffer.from([7, 8, 9]));
    expect(result.audioError).toBeUndefined();
  });

  it("keeps the committed turn when TTS fails", async () => {
    const { service, conversationService } = setup({
      ttsError: new ProviderError("tts down"),
    });
    const result = await service.voiceTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-9",
      pcm: new Uint8Array([1, 2, 3, 4]),
    });
    // Turn committed normally; only the audio payload degrades.
    expect(result.turn).toEqual(turnResult);
    expect(conversationService.processTurn).toHaveBeenCalledOnce();
    expect(result.audio).toBeNull();
    expect(result.audioError).toBe("Voice output unavailable, showing text.");
  });
});

describe("VoiceTurnService.openingTurn", () => {
  function openingSetup(overrides: {
    hasConversation?: boolean;
    opening?: { assistantMessage: string };
    openingError?: unknown;
    ttsAudio?: Uint8Array | null;
    ttsError?: unknown;
  } = {}) {
    const repository = {
      findById: vi.fn().mockResolvedValue(
        overrides.hasConversation === false ? null : { id: "conv-1" },
      ),
    } as unknown as ConversationRepository;
    const conversationService = {
      generateOpening: overrides.openingError
        ? vi.fn().mockRejectedValue(overrides.openingError)
        : vi.fn().mockResolvedValue(
            overrides.opening ?? { assistantMessage: "Welcome in." },
          ),
    } as unknown as ConversationService;
    const tts = {
      synthesize: overrides.ttsError
        ? vi.fn().mockRejectedValue(overrides.ttsError)
        : vi.fn().mockResolvedValue({
            pcm: overrides.ttsAudio !== undefined ? overrides.ttsAudio : Buffer.from([7, 7]),
          }),
    };
    const service = new VoiceTurnService({
      conversationRepository: repository,
      conversationService,
      asrProvider: { transcribe: vi.fn() } as unknown as QwenAsrProvider,
      tts: tts as never,
      limits: { maxAudioBytes: 1024 },
    });
    return { repository, conversationService, tts, service };
  }

  it("returns the opening message with synthesized audio", async () => {
    const { service, conversationService, tts } = openingSetup();
    const result = await service.openingTurn("conv-1");
    expect(result.assistantMessage).toBe("Welcome in.");
    expect(result.audio).toEqual(Buffer.from([7, 7]));
    expect(result.audioError).toBeUndefined();
    expect(conversationService.generateOpening).toHaveBeenCalledWith("conv-1");
    expect(tts.synthesize).toHaveBeenCalledWith("Welcome in.");
  });

  it("throws ConversationNotFoundError for unknown conversations", async () => {
    const { service } = openingSetup({ hasConversation: false });
    await expect(service.openingTurn("missing")).rejects.toThrow(
      ConversationNotFoundError,
    );
  });

  it("degrades to text when TTS fails", async () => {
    const { service } = openingSetup({ ttsError: new ProviderError("tts down") });
    const result = await service.openingTurn("conv-1");
    expect(result.assistantMessage).toBe("Welcome in.");
    expect(result.audio).toBeNull();
    expect(result.audioError).toBe("Voice output unavailable, showing text.");
  });
});

describe("VoiceTurnService timings", () => {
  it("reports ordered stage timestamps and byte sizes", async () => {
    const { service } = setup({ ttsAudio: Buffer.from([1, 2, 3, 4]) });
    const result = await service.voiceTurn({
      conversationId: "c",
      clientTurnId: "t",
      pcm: new Uint8Array([9, 9, 9, 9]),
    });
    const t = result.timings;
    expect(t.receivedMs).toBeLessThanOrEqual(t.parsedMs);
    expect(t.parsedMs).toBeLessThanOrEqual(t.asrStartMs);
    expect(t.asrStartMs).toBeLessThanOrEqual(t.asrDoneMs);
    expect(t.asrDoneMs).toBeLessThanOrEqual(t.llmStartMs);
    expect(t.llmStartMs).toBeLessThanOrEqual(t.llmDoneMs);
    expect(t.llmDoneMs).toBeLessThanOrEqual(t.ttsStartMs);
    expect(t.ttsStartMs).toBeLessThanOrEqual(t.ttsDoneMs);
    expect(t.inputBytes).toBe(4);
    expect(t.audioBytes).toBe(4);
  });

  it("reports zero audio bytes when TTS fails", async () => {
    const { service } = setup({ ttsError: new ProviderError("tts down") });
    const result = await service.voiceTurn({
      conversationId: "c",
      clientTurnId: "t",
      pcm: new Uint8Array([1, 2, 3, 4]),
    });
    expect(result.audio).toBeNull();
    expect(result.timings.audioBytes).toBe(0);
  });
});

describe("VoiceTurnService stage tags", () => {
  it("leaves validation errors untagged", async () => {
    const { service } = setup({ transcript: null });
    // transcript null → VoiceNoSpeechError (validation, untagged).
    const err = await service
      .voiceTurn({ conversationId: "c", clientTurnId: "t", pcm: new Uint8Array([1]) })
      .catch((e: unknown) => e);
    expect((err as Error).message).toBe("No speech detected");
    const { stageOf } = await import("./service.js");
    expect(stageOf(err)).toBeUndefined();
  });

  it("tags provider stage failures without changing the message", async () => {
    const asr = { transcribe: () => Promise.reject(new Error("asr boom")) };
    const repository = {
      findById: () => Promise.resolve({ id: "c" }),
    };
    const svc = new VoiceTurnService({
      conversationRepository: repository as never,
      conversationService: { processTurn: () => Promise.resolve({}) } as never,
      asrProvider: asr as never,
      tts: { synthesize: () => Promise.resolve({ pcm: Buffer.from([1]) }) } as never,
      limits: { maxAudioBytes: 1024 },
    });
    const err = await svc
      .voiceTurn({ conversationId: "c", clientTurnId: "t", pcm: new Uint8Array([1]) })
      .catch((e: unknown) => e);
    expect((err as Error).message).toBe("asr boom");
    const { stageOf, elapsedOf } = await import("./service.js");
    expect(stageOf(err)).toBe("asr");
    expect(typeof elapsedOf(err)).toBe("number");
  });
});
