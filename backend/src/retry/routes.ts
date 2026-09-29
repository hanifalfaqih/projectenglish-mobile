import type { FastifyInstance } from "fastify";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import { SubmitRetryRequest, type RetryArtifact } from "./schema.js";
import {
  RetryService,
  RetryConversationNotFoundError,
  RetryConversationNotClosedError,
  RetryFeedbackMissingError,
  RetryItemInvalidError,
  RetryReferenceInvalidError,
  RetryNotRetryableError,
  RetryInProgressError,
  RetryNotFoundError,
  RetryAlreadyGeneratedError,
} from "./service.js";

function toResponse(view: {
  retry: RetryArtifact;
  originalQuestion: string;
  originalAnswer: string;
}) {
  const { retry } = view;
  return {
    id: retry.id,
    conversationId: retry.conversationId,
    answerMessageId: retry.answerMessageId,
    questionMessageId: retry.questionMessageId,
    retryClientKey: retry.retryClientKey,
    retryAnswer: retry.retryAnswer,
    feedback: retry.feedback,
    feedbackStatus: retry.feedbackStatus,
    feedbackPromptVersion: retry.feedbackPromptVersion,
    originalQuestion: view.originalQuestion,
    originalAnswer: view.originalAnswer,
    createdAt: retry.createdAt,
    updatedAt: retry.updatedAt,
  };
}

/**
 * M12 retry routes. HTTP-only; delegates orchestration to RetryService.
 * Completely separate from the interview turn routes (/turns) and the M11
 * feedback routes. There is deliberately NO list/history endpoint.
 */
export function registerRetryRoutes(
  app: FastifyInstance,
  deps: { service: RetryService },
): void {
  // Submit a retry answer (create+supersede, generate feedback).
  app.post<{ Params: { id: string } }>(
    "/conversations/:id/retries",
    async (request, reply) => {
      const { id } = request.params as { id: string };

      const parsed = SubmitRetryRequest.safeParse(request.body);
      if (!parsed.success) {
        return reply.status(400).send({ error: "Invalid retry request" });
      }

      try {
        const result = await deps.service.submitRetry({
          conversationId: id,
          answerMessageId: parsed.data.answerMessageId,
          questionMessageId: parsed.data.questionMessageId,
          retryAnswer: parsed.data.retryAnswer,
          retryClientKey: parsed.data.retryClientKey,
        });
        // Exact-duplicate retryClientKey → 200 with the existing artifact.
        // New submission → 201 (feedback "generated" or "failed"). Provider
        // errors do NOT 5xx here: the durable answer is the primary result.
        return reply
          .status(result.created ? 201 : 200)
          .send(toResponse(result));
      } catch (err) {
        return mapError(err, request, reply);
      }
    },
  );

  // Read the current retry for an answer. Never generates.
  app.get<{ Params: { id: string; answerMessageId: string } }>(
    "/conversations/:id/retries/:answerMessageId",
    async (request, reply) => {
      const { id, answerMessageId } = request.params as {
        id: string;
        answerMessageId: string;
      };
      try {
        const view = await deps.service.getCurrentRetry(id, answerMessageId);
        return reply.status(200).send(toResponse(view));
      } catch (err) {
        if (
          err instanceof RetryConversationNotFoundError ||
          err instanceof RetryNotFoundError
        ) {
          return reply.status(404).send({ error: "Retry not found" });
        }
        return mapError(err, request, reply);
      }
    },
  );

  // Regenerate feedback for the current retry (failure recovery).
  app.post<{ Params: { id: string; answerMessageId: string } }>(
    "/conversations/:id/retries/:answerMessageId/feedback",
    async (request, reply) => {
      const { id, answerMessageId } = request.params as {
        id: string;
        answerMessageId: string;
      };
      try {
        const view = await deps.service.regenerateFeedback(
          id,
          answerMessageId,
        );
        return reply.status(200).send(toResponse(view));
      } catch (err) {
        if (
          err instanceof RetryConversationNotFoundError ||
          err instanceof RetryNotFoundError
        ) {
          return reply.status(404).send({ error: "Retry not found" });
        }
        if (err instanceof RetryAlreadyGeneratedError) {
          return reply
            .status(409)
            .send({ error: "Retry feedback already generated" });
        }
        if (err instanceof RetryInProgressError) {
          return reply
            .status(409)
            .send({ error: "Retry generation already in progress" });
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
}

// Shared error mapping for the submit path and shared cases.
function mapError(
  err: unknown,
  request: { log: { error: (e: unknown) => void } },
  reply: {
    status: (code: number) => { send: (body: unknown) => unknown };
  },
): unknown {
  if (err instanceof RetryConversationNotFoundError) {
    return reply.status(404).send({ error: "Conversation not found" });
  }
  if (err instanceof RetryConversationNotClosedError) {
    return reply.status(409).send({ error: "Conversation is not closed" });
  }
  if (err instanceof RetryFeedbackMissingError) {
    return reply
      .status(409)
      .send({ error: "No feedback exists for this conversation" });
  }
  if (err instanceof RetryNotRetryableError) {
    return reply.status(409).send({ error: "This answer is not retryable" });
  }
  if (err instanceof RetryInProgressError) {
    return reply
      .status(409)
      .send({ error: "Retry generation already in progress" });
  }
  if (err instanceof RetryItemInvalidError) {
    return reply.status(409).send({ error: "Retry target is invalid" });
  }
  if (err instanceof RetryReferenceInvalidError) {
    return reply.status(400).send({ error: "Invalid message reference" });
  }
  request.log.error(err);
  return reply.status(500).send({ error: "Internal server error" });
}
