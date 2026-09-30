import type { ConversationRepository } from "../conversation/repository.js";
import {
  ConversationNotFoundError,
  ConversationService,
  type TurnResult,
} from "../conversation/service.js";
import type { QwenTtsSynthesizer } from "./tts.js";
import { isWav, pcm16MonoToWav } from "./audio.js";
import type { QwenAsrProvider } from "./provider.js";

export class VoiceAudioTooLargeError extends Error {
  constructor(public readonly size: number) {
    super(`Voice audio too large: ${size} bytes`);
    this.name = "VoiceAudioTooLargeError";
  }
}

export class VoiceAudioInvalidError extends Error {
  constructor(message = "Invalid voice audio") {
    super(message);
    this.name = "VoiceAudioInvalidError";
  }
}

/** Qwen returned no usable speech: no turn is created. Maps to HTTP 422. */
export class VoiceNoSpeechError extends Error {
  constructor() {
    super("No speech detected");
    this.name = "VoiceNoSpeechError";
  }
}

/**
 * Tags a stage failure with where/when for server logs. Message untouched
 * (client contract and key safety unchanged); the route reads these fields
 * for 502/504 diagnostics only.
 */
function tagStage(err: unknown, stage: string, receivedMs: number): unknown {
  if (typeof err === "object" && err !== null) {
    (err as Record<string, unknown>)["voiceStage"] = stage;
    (err as Record<string, unknown>)["voiceElapsedMs"] = Date.now() - receivedMs;
  }
  return err;
}

/** Stage tag attached by {@link tagStage}; absent for validation errors. */
export function stageOf(err: unknown): string | undefined {
  return typeof err === "object" && err !== null
    ? ((err as Record<string, unknown>)["voiceStage"] as string | undefined)
    : undefined;
}

/** Elapsed ms at failure; absent for validation errors. */
export function elapsedOf(err: unknown): number | undefined {
  return typeof err === "object" && err !== null
    ? ((err as Record<string, unknown>)["voiceElapsedMs"] as number | undefined)
    : undefined;
}

export interface VoiceLimits {
  maxAudioBytes: number;
}

export const DEFAULT_VOICE_LIMITS: VoiceLimits = {
  maxAudioBytes: Number(process.env.VOICE_MAX_BYTES) || 5 * 1024 * 1024,
};

export interface VoiceTurnInput {
  conversationId: string;
  clientTurnId: string;
  /** Raw 16-bit mono 16 kHz PCM bytes, fixed by the client contract. */
  pcm: Uint8Array;
}

export interface VoiceTurnResult {
  transcript: string;
  turn: TurnResult;
  /** Synthesized PCM (16-bit mono 24 kHz) of the assistant message, if TTS succeeded. */
  audio: Buffer | null;
  /** Safe client-readable reason when audio is null despite a good turn. */
  audioError?: string;
  /**
   * Server-side stage timestamps (epoch ms, Date.now()). Internal
   * diagnostics for logs only — never serialized into HTTP responses.
   */
  timings: VoiceTurnTimings;
}

/**
 * Voice-turn pipeline stage boundaries:
 * T0 received → T1 parsed → T2/T3 ASR → T4/T5 LLM → T6/T7 TTS.
 * Encoding (T8) and send (T9) are measured by the route, which owns the
 * base64 payload and the reply lifecycle.
 */
export interface VoiceTurnTimings {
  receivedMs: number;
  parsedMs: number;
  asrStartMs: number;
  asrDoneMs: number;
  llmStartMs: number;
  llmDoneMs: number;
  ttsStartMs: number;
  ttsDoneMs: number;
  /** Uploaded PCM bytes (post-validation). */
  inputBytes: number;
  /** Synthesized PCM bytes (0 when TTS failed). */
  audioBytes: number;
}

export interface OpeningTurnResult {
  assistantMessage: string;
  /** Synthesized PCM (16-bit mono 24 kHz) of the opening message, if TTS succeeded. */
  audio: Buffer | null;
  /** Safe client-readable reason when audio is null despite a good opening. */
  audioError?: string;
}

export interface VoiceTurnServiceDependencies {
  conversationRepository: ConversationRepository;
  conversationService: ConversationService;
  asrProvider: QwenAsrProvider;
  tts: QwenTtsSynthesizer;
  limits?: VoiceLimits;
}

/**
 * Voice-turn orchestration. Transcribes with Qwen ASR, then delegates to
 * the authoritative `ConversationService.processTurn` — the same function
 * behind POST /turns — so idempotency, concurrency protection, closing
 * behavior, and state transitions are identical for voice and text.
 */
export class VoiceTurnService {
  private readonly limits: VoiceLimits;

  constructor(private readonly deps: VoiceTurnServiceDependencies) {
    this.limits = deps.limits ?? DEFAULT_VOICE_LIMITS;
  }

  /**
   * AI-first opening: generates the interviewer's first message via the
   * authoritative conversation service, then synthesizes it with the same
   * non-fatal TTS semantics as voice turns. The conversation must be fresh
   * (no turns yet); replays are safe because generation itself is
   * idempotent for an untouched conversation.
   */
  async openingTurn(conversationId: string): Promise<OpeningTurnResult> {
    const conversation =
      await this.deps.conversationRepository.findById(conversationId);
    if (!conversation) {
      throw new ConversationNotFoundError(conversationId);
    }

    const opening =
      await this.deps.conversationService.generateOpening(conversationId);

    // TTS runs strictly after the opening commits: synthesis failure
    // degrades the audio payload but never rolls back conversation state.
    let audio: Buffer | null = null;
    let audioError: string | undefined;
    try {
      const tts = await this.deps.tts.synthesize(opening.assistantMessage);
      audio = tts.pcm;
    } catch {
      audioError = "Voice output unavailable, showing text.";
    }
    return { assistantMessage: opening.assistantMessage, audio, audioError };
  }
  async voiceTurn(input: VoiceTurnInput): Promise<VoiceTurnResult> {
    const receivedMs = Date.now();
    if (input.pcm.length === 0) {
      throw new VoiceAudioInvalidError("Empty voice audio");
    }
    if (input.pcm.length > this.limits.maxAudioBytes) {
      throw new VoiceAudioTooLargeError(input.pcm.length);
    }

    const conversation =
      await this.deps.conversationRepository.findById(input.conversationId);
    if (!conversation) {
      throw new ConversationNotFoundError(input.conversationId);
    }

    const wav = isWav(input.pcm)
      ? Buffer.from(input.pcm)
      : pcm16MonoToWav(input.pcm);
    const parsedMs = Date.now();
    const asrStartMs = parsedMs;
    const transcript = await this.deps.asrProvider
      .transcribe(wav)
      .catch((err: unknown) => {
        throw tagStage(err, "asr", receivedMs);
      });
    const asrDoneMs = Date.now();
    if (!transcript || transcript.trim().length === 0) {
      throw new VoiceNoSpeechError();
    }

    const llmStartMs = Date.now();
    const turn = await this.deps.conversationService
      .processTurn({
        conversationId: input.conversationId,
        clientTurnId: input.clientTurnId,
        message: transcript,
      })
      .catch((err: unknown) => {
        throw tagStage(err, "llm", receivedMs);
      });
    const llmDoneMs = Date.now();

    // TTS runs strictly after the turn commits: synthesis failure degrades
    // the audio payload but never rolls back conversation state.
    const ttsStartMs = Date.now();
    let audio: Buffer | null = null;
    let audioError: string | undefined;
    try {
      const tts = await this.deps.tts.synthesize(turn.assistantMessage);
      audio = tts.pcm;
    } catch {
      audioError = "Voice output unavailable, showing text.";
    }
    const ttsDoneMs = Date.now();
    return {
      transcript,
      turn,
      audio,
      audioError,
      timings: {
        receivedMs,
        parsedMs,
        asrStartMs,
        asrDoneMs,
        llmStartMs,
        llmDoneMs,
        ttsStartMs,
        ttsDoneMs,
        inputBytes: input.pcm.length,
        audioBytes: audio?.length ?? 0,
      },
    };
  }
}
