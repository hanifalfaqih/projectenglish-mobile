import { describe, it, expect, vi, beforeEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import { registerFeedbackRoutes } from "./routes.js";
import { FeedbackService } from "./service.js";
import { ProviderError, ProviderTimeoutError } from "../conversation/service.js";
import type {
  ConversationRepository,
  ConversationRecord,
  MessageRecord,
} from "../conversation/repository.js";
import type { FeedbackRepository, PersistFeedbackInput } from "./repository.js";
import type { FeedbackProvider, FeedbackOutputRaw } from "./provider.js";
import type { FeedbackArtifact } from "./schema.js";

const T0 = Date.UTC(2026, 0, 1, 0, 0, 0);
const ts = (s: number) => new Date(T0 + s * 1000);

function conv(overrides: Partial<ConversationRecord> = {}): ConversationRecord {
  return {
    id: "conv-1",
    status: "closed",
    state: { phase: "wrap_up", topics: [], currentTopicId: null, questionCount: 8 },
    stateVersion: 9,
    experienceProfileId: null,
    createdAt: ts(0),
    updatedAt: ts(1),
    ...overrides,
  };
}

const MESSAGES: MessageRecord[] = [
  {
    id: "a1", conversationId: "conv-1", role: "assistant",
    content: "Q?", promptVersion: "2.0.0", clientTurnId: null, createdAt: ts(0),
  },
  {
    id: "u1", conversationId: "conv-1", role: "user",
    content: "A.", promptVersion: null, clientTurnId: "t1", createdAt: ts(1),
  },
];

const OUTPUT: FeedbackOutputRaw = {
  overall: "Solid answers.",
  answerItems: [
    {
      answerMessageId: "u1",
      questionMessageId: "a1",
      practiceOpportunity: true,
      whatWorked: "clear",
    },
  ],
};

class InMemoryFeedbackRepo {
  private store = new Map<string, FeedbackArtifact>();
  async findByConversationId(id: string) {
    return this.store.get(id) ?? null;
  }
  async create(input: PersistFeedbackInput): Promise<FeedbackArtifact> {
    if (this.store.has(input.conversationId)) {
      throw Object.assign(new Error("unique"), { code: "P2002" });
    }
    const a: FeedbackArtifact = {
      conversationId: input.conversationId,
      promptVersion: input.promptVersion,
      overall: input.overall,
      answerItems: input.answerItems,
      professionalCommunication: input.professionalCommunication,
      createdAt: ts(2),
    };
    this.store.set(input.conversationId, a);
    return a;
  }
  seed(a: FeedbackArtifact) {
    this.store.set(a.conversationId, a);
  }
}

function build(opts: {
  conversation?: ConversationRecord | null;
  provider?: FeedbackProvider;
  feedbackRepo?: InMemoryFeedbackRepo;
}): { app: FastifyInstance; feedbackRepo: InMemoryFeedbackRepo } {
  const feedbackRepo = opts.feedbackRepo ?? new InMemoryFeedbackRepo();
  const conversationRepository = {
    findById: vi.fn().mockResolvedValue(
      opts.conversation === undefined ? conv() : opts.conversation,
    ),
    loadAllMessages: vi.fn().mockResolvedValue(MESSAGES),
  } as unknown as ConversationRepository;
  const provider: FeedbackProvider =
    opts.provider ?? { generateFeedback: vi.fn().mockResolvedValue(OUTPUT) };

  const service = new FeedbackService({
    conversationRepository,
    feedbackRepository: feedbackRepo as unknown as FeedbackRepository,
    provider,
  });
  const app = Fastify();
  registerFeedbackRoutes(app, { service });
  return { app, feedbackRepo };
}

describe("POST /conversations/:id/feedback", () => {
  it("returns 201 when newly generated", async () => {
    const { app } = build({});
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/conv-1/feedback" });
    expect(res.statusCode).toBe(201);
    expect(res.json().overall).toBe("Solid answers.");
  });

  it("returns 200 when an artifact already exists", async () => {
    const repo = new InMemoryFeedbackRepo();
    repo.seed({
      conversationId: "conv-1", promptVersion: "feedback-1.0.0",
      overall: "Existing.", answerItems: [], professionalCommunication: null, createdAt: ts(2),
    });
    const provider: FeedbackProvider = { generateFeedback: vi.fn() };
    const { app } = build({ feedbackRepo: repo, provider });
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/conv-1/feedback" });
    expect(res.statusCode).toBe(200);
    expect(res.json().overall).toBe("Existing.");
    expect(provider.generateFeedback).not.toHaveBeenCalled();
  });

  it("returns 404 when conversation not found", async () => {
    const { app } = build({ conversation: null });
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/missing/feedback" });
    expect(res.statusCode).toBe(404);
  });

  it("returns 409 when conversation not closed", async () => {
    const { app } = build({ conversation: conv({ status: "active" }) });
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/conv-1/feedback" });
    expect(res.statusCode).toBe(409);
  });

  it("returns 502 on provider error", async () => {
    const { app } = build({
      provider: { generateFeedback: vi.fn().mockRejectedValue(new ProviderError("x")) },
    });
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/conv-1/feedback" });
    expect(res.statusCode).toBe(502);
  });

  it("returns 504 on provider timeout", async () => {
    const { app } = build({
      provider: { generateFeedback: vi.fn().mockRejectedValue(new ProviderTimeoutError("x")) },
    });
    await app.ready();
    const res = await app.inject({ method: "POST", url: "/conversations/conv-1/feedback" });
    expect(res.statusCode).toBe(504);
  });
});

describe("GET /conversations/:id/feedback", () => {
  it("returns 200 with existing feedback", async () => {
    const repo = new InMemoryFeedbackRepo();
    repo.seed({
      conversationId: "conv-1", promptVersion: "feedback-1.0.0",
      overall: "Existing.", answerItems: [], professionalCommunication: null, createdAt: ts(2),
    });
    const { app } = build({ feedbackRepo: repo });
    await app.ready();
    const res = await app.inject({ method: "GET", url: "/conversations/conv-1/feedback" });
    expect(res.statusCode).toBe(200);
    expect(res.json().overall).toBe("Existing.");
  });

  it("returns 404 when no feedback exists (never generates)", async () => {
    const provider: FeedbackProvider = { generateFeedback: vi.fn() };
    const { app } = build({ provider });
    await app.ready();
    const res = await app.inject({ method: "GET", url: "/conversations/conv-1/feedback" });
    expect(res.statusCode).toBe(404);
    expect(provider.generateFeedback).not.toHaveBeenCalled();
  });

  it("returns 404 when the conversation does not exist", async () => {
    const { app } = build({ conversation: null });
    await app.ready();
    const res = await app.inject({ method: "GET", url: "/conversations/missing/feedback" });
    expect(res.statusCode).toBe(404);
  });
});
