import { describe, it, expect, beforeAll, afterAll } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import { PrismaClient } from "@prisma/client";
import { ConversationRepository } from "./repository.js";
import { ConversationService, TurnInProgressError } from "./service.js";
import { QwenProvider, createQwenProviderConfig } from "./provider/qwen.js";
import { registerConversationRoutes } from "./routes.js";
import { MAX_QUESTIONS } from "./state/schema.js";

/**
 * OPT-IN real-stack integration tests. Never part of the default suite:
 *   - excluded from vitest.config.ts by the `*.live.test.ts` suffix
 *   - additionally self-skips unless RUN_LIVE_INTEGRATION=1 and the required
 *     environment (DATABASE_URL + DASHSCOPE_API_KEY) is present.
 *
 * Verifies the REAL wiring:
 *   ConversationService -> ConversationRepository -> Prisma/PostgreSQL
 *   ConversationService -> QwenProvider -> Alibaba DashScope
 *   HTTP -> Fastify -> ... (full-stack smoke, matching the Next.js /api proxy target)
 *
 * Because LLM output is nondeterministic, these tests assert INVARIANTS and
 * WIRING (state persists, idempotency holds, the conversation eventually
 * completes), NOT an exact phase/message sequence.
 *
 * Secrets are read from the environment only and are never logged.
 */

const LIVE =
  process.env.RUN_LIVE_INTEGRATION === "1" &&
  Boolean(process.env.DATABASE_URL) &&
  Boolean(process.env.DASHSCOPE_API_KEY);

if (!LIVE) {
  // Visible reason without leaking any secret values.
  // eslint-disable-next-line no-console
  console.info(
    "[live-integration] skipped: set RUN_LIVE_INTEGRATION=1 and provide DATABASE_URL + DASHSCOPE_API_KEY to run.",
  );
}

describe.skipIf(!LIVE)("M8 live real-stack integration", () => {
  let prisma: PrismaClient;
  let repository: ConversationRepository;
  let service: ConversationService;
  let app: FastifyInstance;
  const createdConversationIds: string[] = [];

  beforeAll(async () => {
    prisma = new PrismaClient();
    repository = new ConversationRepository(prisma);
    service = new ConversationService({
      repository,
      provider: new QwenProvider(createQwenProviderConfig()),
    });

    app = Fastify();
    registerConversationRoutes(app, { repository, service });
    await app.ready();
  });

  afterAll(async () => {
    for (const id of createdConversationIds) {
      await prisma.message
        .deleteMany({ where: { conversationId: id } })
        .catch(() => {});
      await prisma.conversation.delete({ where: { id } }).catch(() => {});
    }
    await app?.close();
    await prisma?.$disconnect();
  });

  it("real Service -> Repository -> PostgreSQL: a turn persists state and messages", async () => {
    const conv = await repository.create();
    createdConversationIds.push(conv.id);

    const result = await service.processTurn({
      conversationId: conv.id,
      clientTurnId: "live-persist-1",
      message: "Hi, I'm here to talk about my internship experience.",
    });

    expect(typeof result.assistantMessage).toBe("string");
    expect(result.assistantMessage.length).toBeGreaterThan(0);

    // Persistence consistency in real Postgres.
    const persisted = await repository.findById(conv.id);
    expect(persisted).not.toBeNull();
    expect(persisted!.stateVersion).toBe(2); // 1 -> 2 after one turn
    const rows = await prisma.message.count({
      where: { conversationId: conv.id },
    });
    expect(rows).toBe(2); // user + assistant
  });

  it("real provider: idempotent replay returns same answer and adds no rows", async () => {
    const conv = await repository.create();
    createdConversationIds.push(conv.id);
    const clientTurnId = "live-idem-1";
    const message = "I worked on a mobile app during my last internship.";

    const first = await service.processTurn({
      conversationId: conv.id,
      clientTurnId,
      message,
    });
    const countAfterFirst = await prisma.message.count({
      where: { conversationId: conv.id },
    });

    const second = await service.processTurn({
      conversationId: conv.id,
      clientTurnId,
      message,
    });
    const countAfterSecond = await prisma.message.count({
      where: { conversationId: conv.id },
    });

    expect(second.assistantMessage).toBe(first.assistantMessage);
    expect(countAfterSecond).toBe(countAfterFirst);
    const userRows = await prisma.message.count({
      where: { conversationId: conv.id, clientTurnId, role: "user" },
    });
    expect(userRows).toBe(1);
  });

  it("real lifecycle: the conversation eventually reaches wrap_up and closed", async () => {
    const conv = await repository.create();
    createdConversationIds.push(conv.id);

    let status: "active" | "closed" = "active";
    let sawWrapUp = false;
    let sawClosing = false;

    // Bounded loop with headroom. We do NOT assert an exact phase order — only
    // that the deterministic backend rules (questionCount -> wrap_up -> close)
    // drive the conversation to completion regardless of LLM wording.
    for (let i = 0; i < MAX_QUESTIONS + 5 && status === "active"; i++) {
      const result = await service.processTurn({
        conversationId: conv.id,
        clientTurnId: `live-life-${i}`,
        message: `Here is answer number ${i} about my project work and what I learned.`,
      });
      if (result.state.phase === "wrap_up") sawWrapUp = true;
      if (result.closing) sawClosing = true;
      status = result.status;
    }

    expect(sawWrapUp).toBe(true);
    expect(sawClosing).toBe(true);
    expect(status).toBe("closed");

    const persisted = await repository.findById(conv.id);
    expect(persisted!.status).toBe("closed");
    expect(persisted!.state.questionCount).toBeGreaterThanOrEqual(MAX_QUESTIONS);
  });

  it("real concurrency: overlapping turns -> exactly one succeeds (one-active-turn guard)", async () => {
    const conv = await repository.create();
    createdConversationIds.push(conv.id);

    const results = await Promise.allSettled([
      service.processTurn({
        conversationId: conv.id,
        clientTurnId: "live-c-A",
        message: "Concurrent answer A about my experience.",
      }),
      service.processTurn({
        conversationId: conv.id,
        clientTurnId: "live-c-B",
        message: "Concurrent answer B about my experience.",
      }),
    ]);

    const fulfilled = results.filter((r) => r.status === "fulfilled");
    const rejected = results.filter((r) => r.status === "rejected");
    expect(fulfilled).toHaveLength(1);
    expect(rejected).toHaveLength(1);
    expect((rejected[0] as PromiseRejectedResult).reason).toBeInstanceOf(
      TurnInProgressError,
    );
  });

  it("full-stack HTTP smoke: start interview -> submit one answer -> assistant + state update", async () => {
    // Mirrors the path the Next.js /api proxy forwards to Fastify.
    const createRes = await app.inject({
      method: "POST",
      url: "/conversations",
    });
    expect(createRes.statusCode).toBe(201);
    const created = createRes.json();
    createdConversationIds.push(created.id);
    expect(created.status).toBe("active");
    expect(created.state.questionCount).toBe(0);

    const turnRes = await app.inject({
      method: "POST",
      url: `/conversations/${created.id}/turns`,
      payload: {
        clientTurnId: "live-smoke-1",
        message: "Hello, I would like to start the interview.",
      },
    });
    expect(turnRes.statusCode).toBe(200);
    const body = turnRes.json();

    expect(typeof body.assistantMessage).toBe("string");
    expect(body.assistantMessage.length).toBeGreaterThan(0);
    expect(body.state).toHaveProperty("phase");
    expect(body.state).toHaveProperty("questionCount");
    expect(["active", "closed"]).toContain(body.status);
  });
});
