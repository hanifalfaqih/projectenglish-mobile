import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ConversationRepository } from "./repository.js";
import {
  ConversationService,
  ConversationNotFoundError,
  ConversationClosedError,
  ExperienceProfileNotFoundError,
  ProviderError,
  ProviderTimeoutError,
  TurnInProgressError,
} from "./service.js";
import type { ExperienceProfileRepository } from "../experience/repository.js";
import {
  CreateExperienceProfileInput,
  checkPayloadBound,
} from "../experience/schema.js";

const TurnRequestSchema = z.object({
  clientTurnId: z.string().min(1),
  message: z.string().min(1),
});

const CreateConversationSchema = z
  .object({
    experienceProfileId: z.string().min(1).optional(),
  })
  .strict();

export function registerConversationRoutes(
  app: FastifyInstance,
  deps: {
    repository: ConversationRepository;
    service: ConversationService;
    experienceProfiles?: ExperienceProfileRepository;
  },
): void {
  if (deps.experienceProfiles) {
    const experienceRepo = deps.experienceProfiles;

    app.post("/experience-profiles", async (request, reply) => {
      const parsed = CreateExperienceProfileInput.safeParse(request.body);
      if (!parsed.success) {
        return reply.status(400).send({
          error: "Invalid request",
          details: parsed.error.flatten(),
        });
      }

      const payload = checkPayloadBound(parsed.data);
      if (!payload.ok) {
        return reply.status(400).send({
          error: "Experience profile payload too large",
          details: { size: payload.size, max: payload.max },
        });
      }

      const profile = await experienceRepo.create(parsed.data);
      return reply.status(201).send({ id: profile.id });
    });
  }

  app.post("/conversations", async (request, reply) => {
    // Body is optional; when present it may carry an experienceProfileId.
    const parsed = CreateConversationSchema.safeParse(request.body ?? {});
    if (!parsed.success) {
      return reply.status(400).send({
        error: "Invalid request",
        details: parsed.error.flatten(),
      });
    }

    try {
      const conversation = await deps.service.createConversation({
        experienceProfileId: parsed.data.experienceProfileId,
      });

      return reply.status(201).send({
        id: conversation.id,
        status: conversation.status,
        state: conversation.state,
      });
    } catch (err) {
      if (err instanceof ExperienceProfileNotFoundError) {
        return reply.status(404).send({ error: "Experience profile not found" });
      }
      request.log.error(err);
      return reply.status(500).send({ error: "Internal server error" });
    }
  });

  app.post<{ Params: { id: string } }>(
    "/conversations/:id/turns",
    async (request, reply) => {
      const { id } = request.params as { id: string };

      const parsed = TurnRequestSchema.safeParse(request.body);
      if (!parsed.success) {
        return reply.status(400).send({
          error: "Invalid request",
          details: parsed.error.flatten(),
        });
      }

      try {
        const result = await deps.service.processTurn({
          conversationId: id,
          clientTurnId: parsed.data.clientTurnId,
          message: parsed.data.message,
        });

        return reply.status(200).send({
          assistantMessage: result.assistantMessage,
          state: {
            phase: result.state.phase,
            topics: result.state.topics,
            currentTopicId: result.state.currentTopicId,
            questionCount: result.state.questionCount,
          },
          status: result.status,
          closing: result.closing,
        });
      } catch (err) {
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

  // M18 — Active Interview Recovery. Read-only rehydration endpoint. Composed
  // from EXISTING repository reads (findById + loadAllMessages); it never
  // mutates state, changes status, appends messages, calls the LLM/provider,
  // or generates feedback/retries. HTTP semantics are LOCKED (contract §8):
  //   200 → conversation exists (body.status disambiguates active/closed)
  //   404 → conversation does not exist
  //   500 → unexpected failure
  // A closed conversation is an existing resource → 200 with status "closed"
  // (never 404/409); the client decides it is ineligible for active recovery.
  app.get<{ Params: { id: string } }>(
    "/conversations/:id",
    async (request, reply) => {
      const { id } = request.params as { id: string };
      try {
        const conversation = await deps.repository.findById(id);
        if (!conversation) {
          return reply.status(404).send({ error: "Conversation not found" });
        }

        // Full, ordered transcript (createdAt asc, id asc); system excluded.
        const messages = await deps.repository.loadAllMessages(id);
        const transcript = messages
          .filter((m) => m.role === "user" || m.role === "assistant")
          .map((m) => ({
            id: m.id,
            role: m.role as "user" | "assistant",
            content: m.content,
            createdAt: m.createdAt,
          }));

        return reply.status(200).send({
          id: conversation.id,
          status: conversation.status,
          state: {
            phase: conversation.state.phase,
            topics: conversation.state.topics,
            currentTopicId: conversation.state.currentTopicId,
            questionCount: conversation.state.questionCount,
          },
          experienceProfileId: conversation.experienceProfileId,
          transcript,
        });
      } catch (err) {
        request.log.error(err);
        return reply.status(500).send({ error: "Internal server error" });
      }
    },
  );
}
