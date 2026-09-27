import { describe, it, expect, vi, beforeEach } from "vitest";
import Fastify from "fastify";
import type { FastifyInstance } from "fastify";
import { registerConversationRoutes } from "./routes.js";
import type { ConversationRepository, ConversationRecord, MessageRecord } from "./repository.js";
import {
  ConversationService,
  ConversationNotFoundError,
  ConversationClosedError,
  ProviderError,
  ProviderTimeoutError,
  TurnInProgressError,
} from "./service.js";
import type { LLMProvider } from "./provider/index.js";

function makeConversation(overrides: Partial<ConversationRecord> = {}): ConversationRecord {
  return {
    id: "conv-1",
    status: "active",
    state: { phase: "intro", topics: [], currentTopicId: null, questionCount: 0 },
    stateVersion: 1,
    experienceProfileId: null,
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides,
  };
}

function createMockRepo(): ConversationRepository {
  return {
    create: vi.fn().mockResolvedValue(makeConversation()),
    findById: vi.fn().mockResolvedValue(makeConversation()),
    findUserMessageByClientTurnId: vi.fn().mockResolvedValue(null),
    findFirstAssistantAfter: vi.fn().mockResolvedValue(null),
    appendUserMessage: vi.fn().mockResolvedValue({
      id: "msg-1",
      conversationId: "conv-1",
      role: "user",
      content: "Hello",
      promptVersion: null,
      clientTurnId: "turn-1",
      createdAt: new Date(),
    } satisfies MessageRecord),
    loadRecentMessages: vi.fn().mockResolvedValue([]),
    loadAllMessages: vi.fn().mockResolvedValue([]),
    persistTurn: vi.fn().mockResolvedValue({
      id: "msg-2",
      conversationId: "conv-1",
      role: "assistant",
      content: "Welcome!",
      promptVersion: "1.0.0",
      clientTurnId: null,
      createdAt: new Date(),
    } satisfies MessageRecord),
  } as unknown as ConversationRepository;
}

function createMockProvider(): LLMProvider {
  return {
    generateTurn: vi.fn().mockResolvedValue({
      assistantMessage: "Welcome to the interview!",
    }),
  };
}

describe("POST /conversations", () => {
  let app: FastifyInstance;
  let repo: ConversationRepository;

  beforeEach(async () => {
    app = Fastify();
    repo = createMockRepo();
    const provider = createMockProvider();
    const service = new ConversationService({ repository: repo, provider });
    registerConversationRoutes(app, { repository: repo, service });
    await app.ready();
  });

  it("creates a conversation and returns 201", async () => {
    const res = await app.inject({ method: "POST", url: "/conversations" });
    expect(res.statusCode).toBe(201);

    const body = res.json();
    expect(body.id).toBe("conv-1");
    expect(body.status).toBe("active");
    expect(body.state).toEqual({
      phase: "intro",
      topics: [],
      currentTopicId: null,
      questionCount: 0,
    });
    expect(repo.create).toHaveBeenCalledOnce();
  });
});

describe("POST /conversations/:id/turns", () => {
  let app: FastifyInstance;
  let repo: ConversationRepository;
  let provider: LLMProvider;

  beforeEach(async () => {
    app = Fastify();
    repo = createMockRepo();
    provider = createMockProvider();
    const service = new ConversationService({ repository: repo, provider });
    registerConversationRoutes(app, { repository: repo, service });
    await app.ready();
  });

  it("processes a turn and returns 200", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-1", message: "Hello" },
    });

    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.assistantMessage).toBe("Welcome to the interview!");
    expect(body.state).toBeDefined();
    expect(body.status).toBe("active");
  });

  it("returns 400 for invalid request (missing clientTurnId)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { message: "Hello" },
    });
    expect(res.statusCode).toBe(400);
  });

  it("returns 400 for invalid request (empty message)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-1", message: "" },
    });
    expect(res.statusCode).toBe(400);
  });

  it("returns 400 for missing body", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
    });
    expect(res.statusCode).toBe(400);
  });

  it("returns 404 when conversation not found", async () => {
    vi.mocked(repo.findById).mockResolvedValue(null);

    const res = await app.inject({
      method: "POST",
      url: "/conversations/missing-id/turns",
      payload: { clientTurnId: "turn-1", message: "Hello" },
    });
    expect(res.statusCode).toBe(404);
  });

  it("returns 409 when conversation is closed", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ status: "closed" }),
    );

    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-1", message: "Hello" },
    });
    expect(res.statusCode).toBe(409);
  });

  it("replays the persisted closing reply with 200 for the same clientTurnId on a closed conversation (M21 AC-01)", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ status: "closed" }),
    );
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue({
      id: "msg-u1",
      conversationId: "conv-1",
      role: "user",
      content: "My final answer",
      promptVersion: null,
      clientTurnId: "turn-closing",
      createdAt: new Date("2026-01-01T10:00:00Z"),
    } satisfies MessageRecord);
    vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue({
      id: "msg-a1",
      conversationId: "conv-1",
      role: "assistant",
      content: "Thank you for your time today!",
      promptVersion: "1.0.0",
      clientTurnId: null,
      createdAt: new Date("2026-01-01T10:00:05Z"),
    } satisfies MessageRecord);

    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-closing", message: "My final answer" },
    });

    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.assistantMessage).toBe("Thank you for your time today!");
    expect(body.status).toBe("closed");
    expect(body.closing).toBe(true);
    expect(provider.generateTurn).not.toHaveBeenCalled();
    expect(repo.appendUserMessage).not.toHaveBeenCalled();
    expect(repo.persistTurn).not.toHaveBeenCalled();
  });

  it("returns 409 for a new clientTurnId on a closed conversation without writing (M21 AC-03)", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ status: "closed" }),
    );
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);

    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-brand-new", message: "One more thing" },
    });

    expect(res.statusCode).toBe(409);
    expect(res.json().error).toContain("Conversation is closed");
    expect(repo.appendUserMessage).not.toHaveBeenCalled();
    expect(repo.persistTurn).not.toHaveBeenCalled();
  });

  it("returns 409 when turn is already in progress", async () => {
    // Create a slow provider that won't finish quickly
    const slowProvider: LLMProvider = {
      generateTurn: vi.fn().mockImplementation(
        () => new Promise(() => {}), // never resolves
      ),
    };

    const slowApp = Fastify();
    const slowService = new ConversationService({
      repository: repo,
      provider: slowProvider,
    });
    registerConversationRoutes(slowApp, { repository: repo, service: slowService });
    await slowApp.ready();

    // Start first turn (don't await)
    const p1 = slowApp.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-A", message: "First" },
    });

    // Give it a moment to start
    await new Promise((r) => setTimeout(r, 20));

    // Second turn should get 409
    const res2 = await slowApp.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-B", message: "Second" },
    });
    expect(res2.statusCode).toBe(409);

    // Cleanup: close the slow app (p1 will fail due to timeout)
    await slowApp.close();
  });

  it("returns 502 on provider error", async () => {
    vi.mocked(provider.generateTurn).mockRejectedValue(
      new ProviderError("LLM failed"),
    );

    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-1", message: "Hello" },
    });
    expect(res.statusCode).toBe(502);
  });

  it("returns 504 on provider timeout", async () => {
    vi.mocked(provider.generateTurn).mockRejectedValue(
      new ProviderTimeoutError("Timed out"),
    );

    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-1", message: "Hello" },
    });
    expect(res.statusCode).toBe(504);
  });

  it("returns 500 on unexpected error", async () => {
    vi.mocked(repo.findById).mockRejectedValue(new Error("DB crash"));

    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-1", message: "Hello" },
    });
    expect(res.statusCode).toBe(500);
  });

  it("returns state view in response", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/conversations/conv-1/turns",
      payload: { clientTurnId: "turn-1", message: "Hello" },
    });

    const body = res.json();
    expect(body.state).toHaveProperty("phase");
    expect(body.state).toHaveProperty("topics");
    expect(body.state).toHaveProperty("currentTopicId");
    expect(body.state).toHaveProperty("questionCount");
  });
});

import type { ExperienceProfileRepository } from "../experience/repository.js";
import type { ExperienceProfile } from "../experience/schema.js";

function makeProfile(): ExperienceProfile {
  return {
    id: "prof-1",
    items: [
      {
        id: "it-1",
        title: "Backend Intern",
        organization: null,
        role: null,
        description: "Built APIs.",
        skills: [],
        position: 0,
      },
    ],
    createdAt: new Date(),
    updatedAt: new Date(),
  };
}

function createMockExperienceRepo(): ExperienceProfileRepository {
  return {
    create: vi.fn().mockResolvedValue(makeProfile()),
    findById: vi.fn().mockResolvedValue(makeProfile()),
  } as unknown as ExperienceProfileRepository;
}

describe("POST /experience-profiles (M9)", () => {
  let app: FastifyInstance;
  let repo: ConversationRepository;
  let experienceProfiles: ExperienceProfileRepository;

  beforeEach(async () => {
    app = Fastify();
    repo = createMockRepo();
    experienceProfiles = createMockExperienceRepo();
    const service = new ConversationService({
      repository: repo,
      provider: createMockProvider(),
      experienceProfiles,
    });
    registerConversationRoutes(app, { repository: repo, service, experienceProfiles });
    await app.ready();
  });

  it("creates a profile and returns 201 with an id", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/experience-profiles",
      payload: {
        items: [{ title: "Backend Intern", description: "Built APIs." }],
      },
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().id).toBe("prof-1");
    expect(experienceProfiles.create).toHaveBeenCalledOnce();
  });

  it("returns 400 for an invalid profile (no items)", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/experience-profiles",
      payload: { items: [] },
    });
    expect(res.statusCode).toBe(400);
    expect(experienceProfiles.create).not.toHaveBeenCalled();
  });

  it("returns 400 for unknown fields", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/experience-profiles",
      payload: {
        items: [{ title: "t", description: "d" }],
        displayName: "Jane",
      },
    });
    expect(res.statusCode).toBe(400);
  });
});

describe("POST /conversations with experience profile (M9)", () => {
  let app: FastifyInstance;
  let repo: ConversationRepository;
  let experienceProfiles: ExperienceProfileRepository;

  beforeEach(async () => {
    app = Fastify();
    repo = createMockRepo();
    experienceProfiles = createMockExperienceRepo();
    const service = new ConversationService({
      repository: repo,
      provider: createMockProvider(),
      experienceProfiles,
    });
    registerConversationRoutes(app, { repository: repo, service, experienceProfiles });
    await app.ready();
  });

  it("creates a conversation without a profile (201)", async () => {
    const res = await app.inject({ method: "POST", url: "/conversations" });
    expect(res.statusCode).toBe(201);
    expect(repo.create).toHaveBeenCalledWith(null);
  });

  it("creates a conversation with a valid profile (201)", async () => {
    vi.mocked(repo.create).mockResolvedValue({
      id: "conv-1",
      status: "active",
      state: { phase: "intro", topics: [], currentTopicId: null, questionCount: 0 },
      stateVersion: 1,
      experienceProfileId: "prof-1",
      createdAt: new Date(),
      updatedAt: new Date(),
    });

    const res = await app.inject({
      method: "POST",
      url: "/conversations",
      payload: { experienceProfileId: "prof-1" },
    });
    expect(res.statusCode).toBe(201);
    expect(experienceProfiles.findById).toHaveBeenCalledWith("prof-1");
    expect(repo.create).toHaveBeenCalledWith("prof-1");
  });

  it("returns 404 when the referenced profile does not exist", async () => {
    vi.mocked(experienceProfiles.findById).mockResolvedValue(null);

    const res = await app.inject({
      method: "POST",
      url: "/conversations",
      payload: { experienceProfileId: "missing" },
    });
    expect(res.statusCode).toBe(404);
    expect(repo.create).not.toHaveBeenCalled();
  });

  it("returns 400 for unknown fields in the body", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/conversations",
      payload: { experienceProfileId: "prof-1", foo: "bar" },
    });
    expect(res.statusCode).toBe(400);
  });
});

// --- M18: GET /conversations/:id — active interview recovery (read-only) ---

describe("GET /conversations/:id (M18 recovery)", () => {
  let app: FastifyInstance;
  let repo: ConversationRepository;
  let provider: LLMProvider;

  beforeEach(async () => {
    app = Fastify();
    repo = createMockRepo();
    provider = createMockProvider();
    const service = new ConversationService({ repository: repo, provider });
    registerConversationRoutes(app, { repository: repo, service });
    await app.ready();
  });

  it("returns 200 with the active read model (id, status, state, experienceProfileId, transcript)", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({
        status: "active",
        experienceProfileId: "prof-1",
        state: {
          phase: "deep_dive",
          topics: [{ id: "t1", label: "mobile app", covered: true }],
          currentTopicId: "t1",
          questionCount: 3,
        },
      }),
    );
    vi.mocked(repo.loadAllMessages).mockResolvedValue([
      { id: "a1", conversationId: "conv-1", role: "assistant", content: "Q1?", promptVersion: "1.0.0", clientTurnId: null, createdAt: new Date("2026-01-01T00:00:01Z") },
      { id: "u1", conversationId: "conv-1", role: "user", content: "A1.", promptVersion: null, clientTurnId: "turn-1", createdAt: new Date("2026-01-01T00:00:02Z") },
    ] as MessageRecord[]);

    const res = await app.inject({ method: "GET", url: "/conversations/conv-1" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.id).toBe("conv-1");
    expect(body.status).toBe("active");
    expect(body.experienceProfileId).toBe("prof-1");
    expect(body.state).toEqual({
      phase: "deep_dive",
      topics: [{ id: "t1", label: "mobile app", covered: true }],
      currentTopicId: "t1",
      questionCount: 3,
    });
    expect(body.transcript).toEqual([
      { id: "a1", role: "assistant", content: "Q1?", createdAt: expect.any(String) },
      { id: "u1", role: "user", content: "A1.", createdAt: expect.any(String) },
    ]);
    // No clientTurnId leaked into the read model.
    expect(body.transcript[1]).not.toHaveProperty("clientTurnId");
  });

  it("returns 200 with status closed for an existing closed conversation (never 404/409)", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ status: "closed" }),
    );
    const res = await app.inject({ method: "GET", url: "/conversations/conv-1" });
    expect(res.statusCode).toBe(200);
    expect(res.json().status).toBe("closed");
  });

  it("returns 404 when the conversation does not exist", async () => {
    vi.mocked(repo.findById).mockResolvedValue(null);
    const res = await app.inject({ method: "GET", url: "/conversations/missing" });
    expect(res.statusCode).toBe(404);
  });

  it("returns 500 on an unexpected repository failure", async () => {
    vi.mocked(repo.findById).mockRejectedValue(new Error("DB crash"));
    const res = await app.inject({ method: "GET", url: "/conversations/conv-1" });
    expect(res.statusCode).toBe(500);
  });

  it("excludes system messages and preserves loader order", async () => {
    vi.mocked(repo.loadAllMessages).mockResolvedValue([
      { id: "s0", conversationId: "conv-1", role: "system", content: "sys", promptVersion: null, clientTurnId: null, createdAt: new Date("2026-01-01T00:00:00Z") },
      { id: "a1", conversationId: "conv-1", role: "assistant", content: "Q1?", promptVersion: "1.0.0", clientTurnId: null, createdAt: new Date("2026-01-01T00:00:01Z") },
      { id: "u1", conversationId: "conv-1", role: "user", content: "A1.", promptVersion: null, clientTurnId: "t1", createdAt: new Date("2026-01-01T00:00:02Z") },
    ] as MessageRecord[]);

    const res = await app.inject({ method: "GET", url: "/conversations/conv-1" });
    const body = res.json();
    expect(body.transcript.map((m: { id: string }) => m.id)).toEqual(["a1", "u1"]);
    expect(body.transcript.some((m: { role: string }) => m.role === "system")).toBe(false);
  });

  it("is read-only: invokes only read methods, never mutating/generating ones", async () => {
    await app.inject({ method: "GET", url: "/conversations/conv-1" });
    expect(repo.findById).toHaveBeenCalledWith("conv-1");
    expect(repo.loadAllMessages).toHaveBeenCalledWith("conv-1");
    // No creation, no turn processing, no message append, no LLM call.
    expect(repo.create).not.toHaveBeenCalled();
    expect(repo.appendUserMessage).not.toHaveBeenCalled();
    expect(repo.persistTurn).not.toHaveBeenCalled();
    expect(provider.generateTurn).not.toHaveBeenCalled();
  });
});
