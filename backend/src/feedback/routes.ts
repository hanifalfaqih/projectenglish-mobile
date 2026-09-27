import type { FastifyInstance } from "fastify";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import {
  FeedbackService,
  ConversationNotFoundError,
  ConversationNotClosedError,
  FeedbackInProgressError,
  FeedbackNotFoundError,
  FeedbackGenerationError,
  type FeedbackGenerateResult,
} from "./service.js";
import type { FeedbackArtifact } from "./schema.js";

function toResponse(feedback: FeedbackArtifact) {
  return {
    conversationId: feedback.conversationId,
    promptVersion: feedback.promptVersion,
    overall: feedback.overall,
    answerItems: feedback.answerItems,
    professionalCommunication: feedback.professionalCommunication,
    createdAt: feedback.createdAt,
  };
}

/**
 * M11 feedback routes. HTTP-only; delegates orchestration to FeedbackService.
 * Registered separately from conversation routes to keep the feedback module
 * self-contained.
 */
export function registerFeedbackRoutes(
  app: FastifyInstance,
  deps: { service: FeedbackService },
): void {
  app.post<{ Params: { id: string } }>(
    "/conversations/:id/feedback",
    async (request, reply) => {
      const { id } = request.params as { id: string };
      try {
        const result: FeedbackGenerateResult =
          await deps.service.generateFeedback(id);
        return reply
          .status(result.created ? 201 : 200)
          .send(toResponse(result.feedback));
      } catch (err) {
        if (err instanceof ConversationNotFoundError) {
          return reply.status(404).send({ error: "Conversation not found" });
        }
        if (err instanceof ConversationNotClosedError) {
          return reply
            .status(409)
            .send({ error: "Conversation is not closed" });
        }
        if (err instanceof FeedbackInProgressError) {
          return reply
            .status(409)
            .send({ error: "Feedback generation already in progress" });
        }
        if (err instanceof ProviderTimeoutError) {
          return reply.status(504).send({ error: "Provider timeout" });
        }
        if (
          err instanceof ProviderError ||
          err instanceof FeedbackGenerationError
        ) {
          return reply.status(502).send({ error: "Provider error" });
        }
        request.log.error(err);
        return reply.status(500).send({ error: "Internal server error" });
      }
    },
  );

  app.get<{ Params: { id: string } }>(
    "/conversations/:id/feedback",
    async (request, reply) => {
      const { id } = request.params as { id: string };
      try {
        const feedback = await deps.service.getFeedback(id);
        return reply.status(200).send(toResponse(feedback));
      } catch (err) {
        if (
          err instanceof ConversationNotFoundError ||
          err instanceof FeedbackNotFoundError
        ) {
          return reply.status(404).send({ error: "Feedback not found" });
        }
        request.log.error(err);
        return reply.status(500).send({ error: "Internal server error" });
      }
    },
  );
}
