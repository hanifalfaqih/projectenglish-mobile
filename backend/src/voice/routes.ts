import type { FastifyInstance } from "fastify";
import { z } from "zod";
import {
  ConversationClosedError,
  ConversationNotFoundError,
  OpeningAlreadyTakenError,
  ProviderError,
  ProviderTimeoutError,
  TurnInProgressError,
} from "../conversation/service.js";
import {
  VoiceAudioInvalidError,
  VoiceAudioTooLargeError,
  VoiceNoSpeechError,
  elapsedOf,
  stageOf,
  type VoiceTurnService,
} from "./service.js";

const VoiceTurnFieldsSchema = z
  .object({
    clientTurnId: z.string().min(1),
  })
  .strict();

/**
 * POST /conversations/:id/voice-turn — multipart `audio` (raw 16-bit mono
 * 16 kHz PCM) + text field `clientTurnId` → Qwen3-ASR transcript fed
 * through the authoritative turn pipeline.
 *
 * Success 200 mirrors the text-turn response plus `transcript`. Blank
 * transcripts create no turn and return 422 `no_speech`. All other error
 * semantics (404/409/502/504/500) match POST /turns. Requires
 * `@fastify/multipart` (already registered for /resume/parse).
 */
export function registerVoiceRoutes(
  app: FastifyInstance,
  deps: { service: VoiceTurnService },
): void {
  /**
   * POST /conversations/:id/opening — AI-first opening for a fresh
   * conversation: generates the interviewer's first message and synthesizes
   * it. Success 200 carries the message plus optional PCM audio (same
   * envelope as voice-turn). TTS failure is non-fatal (audio null +
   * audioError). 409 when the conversation already has turns.
   */
  app.post<{ Params: { id: string } }>(
    "/conversations/:id/opening",
    async (request, reply) => {
      const { id } = request.params as { id: string };

      try {
        const result = await deps.service.openingTurn(id);

        return reply.status(200).send({
          assistantMessage: result.assistantMessage,
          audio: result.audio
            ? {
                format: "pcm",
                sampleRateHz: 24000,
                channels: 1,
                data: result.audio.toString("base64"),
              }
            : null,
          ...(result.audioError ? { audioError: result.audioError } : {}),
        });
      } catch (err) {
        if (err instanceof ConversationNotFoundError) {
          return reply.status(404).send({ error: "Conversation not found" });
        }
        if (
          err instanceof ConversationClosedError ||
          err instanceof OpeningAlreadyTakenError
        ) {
          return reply.status(409).send({ error: err.message });
        }
        if (err instanceof ProviderTimeoutError) {
          return reply.status(504).send({ error: "Provider timeout" });
        }
        if (err instanceof ProviderError) {
          return reply.status(502).send({ error: "Provider error" });
        }
        request.log.error(err);
        return reply.status(500).send({ error: "Internal server error" });
      }
    },
  );

  app.post<{ Params: { id: string } }>(
    "/conversations/:id/voice-turn",
    async (request, reply) => {
      const { id } = request.params as { id: string };

      let audio: Buffer | null = null;
      let rawFields: Record<string, unknown> = {};
      try {
        for await (const part of request.parts()) {
          if (part.type === "file" && part.fieldname === "audio") {
            audio = await part.toBuffer();
          } else if (part.type === "field") {
            rawFields = { ...rawFields, [part.fieldname]: part.value };
          }
        }
      } catch (err) {
        if (isTooLargeError(err)) {
          return reply.status(413).send({ error: "Voice audio too large" });
        }
        return reply.status(400).send({ error: "Invalid request" });
      }
      if (!audio) {
        return reply.status(400).send({ error: "Invalid request" });
      }

      const parsed = VoiceTurnFieldsSchema.safeParse(rawFields);
      if (!parsed.success) {
        return reply.status(400).send({
          error: "Invalid request",
          details: parsed.error.flatten(),
        });
      }

      try {
        const result = await deps.service.voiceTurn({
          conversationId: id,
          clientTurnId: parsed.data.clientTurnId,
          pcm: audio,
        });

        const encodeStartMs = Date.now();
        const audioPayload = result.audio
          ? {
              format: "pcm",
              sampleRateHz: 24000,
              channels: 1,
              data: result.audio.toString("base64"),
            }
          : null;
        const encodeDoneMs = Date.now();
        // Server-side stage timings (logs only — never sent to clients).
        // Durations, byte sizes, and derived audio length; no audio content,
        // no transcripts, no credentials.
        const t = result.timings;
        request.log.info(
          {
            voiceTiming: {
              asrMs: t.asrDoneMs - t.asrStartMs,
              llmMs: t.llmDoneMs - t.llmStartMs,
              ttsMs: t.ttsDoneMs - t.ttsStartMs,
              encodeMs: encodeDoneMs - encodeStartMs,
              totalMs: encodeDoneMs - t.receivedMs,
              inputBytes: t.inputBytes,
              audioBytes: t.audioBytes,
              audioSeconds: +(t.audioBytes / 2 / 24000).toFixed(2),
            },
            conversationId: id,
          },
          "voice-turn completed",
        );

        return reply.status(200).send({
          transcript: result.transcript,
          assistantMessage: result.turn.assistantMessage,
          state: {
            phase: result.turn.state.phase,
            topics: result.turn.state.topics,
            currentTopicId: result.turn.state.currentTopicId,
            questionCount: result.turn.state.questionCount,
          },
          status: result.turn.status,
          closing: result.turn.closing,
          audio: audioPayload,
          ...(result.audioError ? { audioError: result.audioError } : {}),
        });
      } catch (err) {
        if (err instanceof VoiceAudioTooLargeError) {
          return reply.status(413).send({ error: "Voice audio too large" });
        }
        if (err instanceof VoiceAudioInvalidError) {
          return reply.status(400).send({ error: err.message });
        }
        if (err instanceof VoiceNoSpeechError) {
          return reply.status(422).send({ error: "No speech detected", code: "no_speech" });
        }
        if (err instanceof ConversationNotFoundError) {
          return reply.status(404).send({ error: "Conversation not found" });
        }
        if (
          err instanceof ConversationClosedError ||
          err instanceof TurnInProgressError
        ) {
          return reply.status(409).send({ error: err.message });
        }
        if (err instanceof ProviderTimeoutError) {
          request.log.warn(
            {
              voiceStageFailed: stageOf(err),
              voiceElapsedMs: elapsedOf(err),
              conversationId: id,
            },
            "voice-turn provider timeout",
          );
          return reply.status(504).send({ error: "Provider timeout" });
        }
        if (err instanceof ProviderError) {
          request.log.warn(
            {
              voiceStageFailed: stageOf(err),
              voiceElapsedMs: elapsedOf(err),
              conversationId: id,
            },
            "voice-turn provider error",
          );
          return reply.status(502).send({ error: "Provider error" });
        }
        request.log.error(err);
        return reply.status(500).send({ error: "Internal server error" });
      }
    },
  );
}

/**
 * POST /transcription — multipart `audio` (raw 16-bit mono 16 kHz PCM) →
 * `{ transcript }`. Transcription ONLY: no conversation is looked up, no turn
 * is created, nothing is persisted.
 *
 * Lets a spoken answer be transcribed for an operation other than a
 * conversation turn. Targeted retry uses this, then submits the transcript to
 * POST /conversations/:id/retries so the retry stays a separate RetryPractice
 * artifact and the original conversation is never mutated.
 *
 * Error semantics mirror the ASR stage of POST /conversations/:id/voice-turn:
 * 400 invalid/missing audio, 413 oversized, 422 no speech, 502 provider
 * failure, 504 provider timeout, 500 unexpected. There is deliberately no
 * 404/409 — no conversation is involved.
 */
export function registerTranscriptionRoutes(
  app: FastifyInstance,
  deps: { service: VoiceTurnService },
): void {
  app.post("/transcription", async (request, reply) => {
    let audio: Buffer | null = null;
    try {
      for await (const part of request.parts()) {
        if (part.type === "file" && part.fieldname === "audio") {
          audio = await part.toBuffer();
          break;
        }
      }
    } catch (err) {
      if (isTooLargeError(err)) {
        return reply.status(413).send({ error: "Voice audio too large" });
      }
      return reply.status(400).send({ error: "Invalid request" });
    }
    if (!audio) {
      return reply.status(400).send({ error: "Invalid request" });
    }

    try {
      const result = await deps.service.transcribeOnly({ pcm: audio });
      return reply.status(200).send({ transcript: result.transcript });
    } catch (err) {
      if (err instanceof VoiceAudioTooLargeError) {
        return reply.status(413).send({ error: "Voice audio too large" });
      }
      if (err instanceof VoiceAudioInvalidError) {
        return reply.status(400).send({ error: err.message });
      }
      if (err instanceof VoiceNoSpeechError) {
        return reply
          .status(422)
          .send({ error: "No speech detected", code: "no_speech" });
      }
      if (err instanceof ProviderTimeoutError) {
        request.log.warn(
          {
            voiceStageFailed: stageOf(err),
            voiceElapsedMs: elapsedOf(err),
          },
          "transcription provider timeout",
        );
        return reply.status(504).send({ error: "Provider timeout" });
      }
      if (err instanceof ProviderError) {
        request.log.warn(
          {
            voiceStageFailed: stageOf(err),
            voiceElapsedMs: elapsedOf(err),
          },
          "transcription provider error",
        );
        return reply.status(502).send({ error: "Provider error" });
      }
      request.log.error(err);
      return reply.status(500).send({ error: "Internal server error" });
    }
  });
}

function isTooLargeError(err: unknown): boolean {
  return (
    typeof err === "object" &&
    err !== null &&
    "code" in err &&
    (err as { code: unknown }).code === "FST_REQ_FILE_TOO_LARGE"
  );
}
