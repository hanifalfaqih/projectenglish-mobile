import { describe, it, expect, vi, beforeEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import { registerRetryRoutes } from "./routes.js";
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
  type RetryView,
} from "./service.js";
import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";

function retryView(overrides: Partial<RetryView["retry"]> = {}): RetryView {
  const t = new Date(Date.UTC(2026, 0, 1));
  return {
    retry: {
      id: "r1",
      conversationId: "conv-1",
      feedbackId: "fb-1",
      answerMessageId: "u1",
      questionMessageId: "a1",
      retryClientKey: "key-1",
      retryAnswer: "Better answer.",
      feedback: { overall: "Improved.", professionalCommunication: null },
      feedbackPromptVersion: "retry-feedback-1.0.0",
      feedbackStatus: "generated",
      supersededAt: null,
      createdAt: t,
      updatedAt: t,
      ...overrides,
    },
    originalQuestion: "Tell me about a challenge?",
    originalAnswer: "Compression was hard.",
  };
}

function buildApp(service: RetryService): FastifyInstance {
  const app = Fastify();
  registerRetryRoutes(app, { service });
  return app;
}

function buildService(
  submit: (input: {
    conversationId: string;
    answerMessageId: string;
    questionMessageId: string;
    retryAnswer: string;
    retryClientKey: string;
  }) => Promise<RetryView & { created: boolean }>,
  current: (conversationId: string, answerMessageId: string) => Promise<RetryView> = async () =>
    retryView(),
  regenerate: (conversationId: string, answerMessageId: string) => Promise<RetryView> = async () =>
    retryView(),
): RetryService {
  return {
    submitRetry: vi.fn(submit),
    getCurrentRetry: vi.fn(current),
    regenerateFeedback: vi.fn(regenerate),
  } as unknown as RetryService;
}

const submitBody = {
  answerMessageId: "u1",
  questionMessageId: "a1",
  retryAnswer: "Better answer.",
  retryClientKey: "key-1",
};

describe("POST /conversations/:id/retries", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("returns 201 with the retry contract shape on creation", async () => {
    const service = buildService(async () => ({ ...retryView(), created: true }));
    const app = buildApp(service);
    await app.ready();
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/retries",
      payload: submitBody,
    });
    expect(res.statusCode).toBe(201);
    const body = res.json() as Record<string, unknown>;
    expect(body).toMatchObject({
      id: "r1",
      conversationId: "conv-1",
      answerMessageId: "u1",
      questionMessageId: "a1",
      retryClientKey: "key-1",
      retryAnswer: "Better answer.",
      feedbackStatus: "generated",
      originalQuestion: "Tell me about a challenge?",
      originalAnswer: "Compression was hard.",
    });
    expect(service.submitRetry).toHaveBeenCalledWith({
      conversationId: "conv-1",
      ...submitBody,
    });
    await app.close();
  });

  it("returns 200 on idempotent replay", async () => {
    const service = buildService(async () => ({ ...retryView(), created: false }));
    const app = buildApp(service);
    await app.ready();
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/retries",
      payload: submitBody,
    });
    expect(res.statusCode).toBe(200);
    await app.close();
  });

  it("returns 400 for malformed bodies", async () => {
    const service = buildService(async () => ({ ...retryView(), created: true }));
    const app = buildApp(service);
    await app.ready();
    for (const payload of [
      { ...submitBody, retryAnswer: "   " },
      { ...submitBody, retryClientKey: "" },
      { ...submitBody, extra: "nope" },
      {},
    ]) {
      const res = await app.inject({
        method: "POST",
        url: "/conversations/conv-1/retries",
        payload,
      });
      expect(res.statusCode).toBe(400);
    }
    await app.close();
  });

  it("maps submit errors to status codes", async () => {
    const cases: [unknown, number][] = [
      [new RetryConversationNotFoundError("conv-1"), 404],
      [new RetryConversationNotClosedError("conv-1"), 409],
      [new RetryFeedbackMissingError("conv-1"), 409],
      [new RetryNotRetryableError("nope"), 409],
      [new RetryInProgressError("conv-1", "u1"), 409],
      [new RetryItemInvalidError("bad target"), 409],
      [new RetryReferenceInvalidError("bad ref"), 400],
    ];
    for (const [error, status] of cases) {
      const service = buildService(async () => {
        throw error;
      });
      const app = buildApp(service);
      await app.ready();
      const res = await app.inject({
        method: "POST",
        url: "/conversations/conv-1/retries",
        payload: submitBody,
      });
      expect(res.statusCode).toBe(status);
      await app.close();
    }
  });
});

describe("GET /conversations/:id/retries/:answerMessageId", () => {
  it("returns 200 with the retry view", async () => {
    const service = buildService(
      async () => ({ ...retryView(), created: true }),
      async () => retryView(),
    );
    const app = buildApp(service);
    await app.ready();
    const res = await app.inject({
      method: "GET",
      url: "/conversations/conv-1/retries/u1",
    });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ id: "r1", answerMessageId: "u1" });
    await app.close();
  });

  it("returns 404 when absent", async () => {
    const service = buildService(
      async () => ({ ...retryView(), created: true }),
      async () => {
        throw new RetryNotFoundError("none");
      },
    );
    const app = buildApp(service);
    await app.ready();
    const res = await app.inject({
      method: "GET",
      url: "/conversations/conv-1/retries/u9",
    });
    expect(res.statusCode).toBe(404);
    await app.close();
  });
});

describe("POST /conversations/:id/retries/:answerMessageId/feedback", () => {
  it("returns 200 on regenerate and maps 409/502", async () => {
    const ok = buildService(
      async () => ({ ...retryView(), created: true }),
      async () => retryView(),
      async () => retryView(),
    );
    const app = buildApp(ok);
    await app.ready();
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/retries/u1/feedback",
    });
    expect(res.statusCode).toBe(200);
    await app.close();

    const cases: [unknown, number][] = [
      [new RetryAlreadyGeneratedError("done"), 409],
      [new RetryInProgressError("conv-1", "u1"), 409],
      [new ProviderTimeoutError("slow"), 504],
      [new ProviderError("down"), 502],
    ];
    for (const [error, status] of cases) {
      const service = buildService(
        async () => ({ ...retryView(), created: true }),
        async () => retryView(),
        async () => {
          throw error;
        },
      );
      const app2 = buildApp(service);
      await app2.ready();
      const res2 = await app2.inject({
        method: "POST",
        url: "/conversations/conv-1/retries/u1/feedback",
      });
      expect(res2.statusCode).toBe(status);
      await app2.close();
    }
  });
});
