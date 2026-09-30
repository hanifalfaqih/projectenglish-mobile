import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  ConversationService,
  ConversationNotFoundError,
  ConversationClosedError,
  OpeningAlreadyTakenError,
  ProviderError,
  TurnInProgressError,
} from "./service.js";
import type { ConversationRepository, ConversationRecord, MessageRecord } from "./repository.js";
import type { LLMProvider } from "./provider/index.js";
import type { ConversationState } from "./state/schema.js";
import type { ExperienceProfileReader } from "./service.js";
import type { PracticeContextItem, PracticeContextReader } from "./practiceContext.js";
import { PROMPT_VERSION } from "./prompts/index.js";

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

function makeMessage(overrides: Partial<MessageRecord> = {}): MessageRecord {
  return {
    id: "msg-1",
    conversationId: "conv-1",
    role: "user",
    content: "Hello",
    promptVersion: null,
    clientTurnId: "turn-1",
    createdAt: new Date(),
    ...overrides,
  };
}

function createMockRepo(): ConversationRepository {
  return {
    findById: vi.fn(),
    findUserMessageByClientTurnId: vi.fn(),
    findFirstAssistantAfter: vi.fn(),
    appendUserMessage: vi.fn(),
    loadRecentMessages: vi.fn(),
    persistTurn: vi.fn(),
  } as unknown as ConversationRepository;
}

function createMockProvider(
  response: { assistantMessage: string; proposal?: unknown } = {
    assistantMessage: "What experience do you have?",
  },
): LLMProvider {
  return {
    generateTurn: vi.fn().mockResolvedValue(response),
  };
}

describe("ConversationService.processTurn", () => {
  let repo: ConversationRepository;
  let provider: LLMProvider;
  let service: ConversationService;

  beforeEach(() => {
    repo = createMockRepo();
    provider = createMockProvider();
    service = new ConversationService({
      repository: repo,
      provider,
      promptVersion: "1.0.0",
    });
  });

  it("throws ConversationNotFoundError when conversation does not exist", async () => {
    vi.mocked(repo.findById).mockResolvedValue(null);

    await expect(
      service.processTurn({
        conversationId: "missing",
        clientTurnId: "turn-1",
        message: "Hello",
      }),
    ).rejects.toThrow(ConversationNotFoundError);
  });

  it("throws ConversationClosedError when conversation is closed (AC-15)", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ status: "closed" }),
    );

    await expect(
      service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      }),
    ).rejects.toThrow(ConversationClosedError);
  });

  it("appends user message and processes turn normally", async () => {
    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([
      makeMessage({ role: "user", content: "Hello" }),
    ]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant", content: "What experience do you have?" }),
    );

    const result = await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(result.assistantMessage).toBe("What experience do you have?");
    expect(result.status).toBe("active");
    expect(repo.appendUserMessage).toHaveBeenCalledWith(
      "conv-1",
      "Hello",
      "turn-1",
    );
    expect(repo.persistTurn).toHaveBeenCalledOnce();
  });

  it("idempotent replay: returns existing result for duplicate clientTurnId (AC-16)", async () => {
    const userCreatedAt = new Date("2026-01-01T00:00:00Z");
    const existingUserMsg = makeMessage({ id: "msg-u1", createdAt: userCreatedAt });
    const existingAssistant = makeMessage({
      id: "msg-a1",
      role: "assistant",
      content: "Previous answer",
    });

    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(existingUserMsg);
    vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(existingAssistant);

    const result = await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(result.assistantMessage).toBe("Previous answer");
    expect(repo.appendUserMessage).not.toHaveBeenCalled();
    expect(provider.generateTurn).not.toHaveBeenCalled();
  });

  it("idempotent replay passes createdAt (not ID) to findFirstAssistantAfter", async () => {
    const userCreatedAt = new Date("2026-01-01T10:00:00Z");
    const existingUserMsg = makeMessage({ id: "msg-u1", createdAt: userCreatedAt });
    const existingAssistant = makeMessage({
      id: "msg-a1",
      role: "assistant",
      content: "Replay answer",
    });

    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(existingUserMsg);
    vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(existingAssistant);

    await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(repo.findFirstAssistantAfter).toHaveBeenCalledWith(
      "conv-1",
      userCreatedAt,
    );
  });

  it("idempotent replay with multiple assistants returns the one for the correct turn", async () => {
    const userTurn1Time = new Date("2026-01-01T10:00:00Z");
    const userTurn2Time = new Date("2026-01-01T10:05:00Z");

    const existingUserMsg = makeMessage({
      id: "msg-u2",
      clientTurnId: "turn-2",
      createdAt: userTurn2Time,
    });

    const assistantForTurn2 = makeMessage({
      id: "msg-a2",
      role: "assistant",
      content: "Answer for turn 2",
      createdAt: new Date("2026-01-01T10:05:01Z"),
    });

    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(existingUserMsg);
    vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(assistantForTurn2);

    const result = await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-2",
      message: "Second message",
    });

    expect(result.assistantMessage).toBe("Answer for turn 2");
    expect(repo.findFirstAssistantAfter).toHaveBeenCalledWith(
      "conv-1",
      userTurn2Time,
    );
  });

  it("resumes interrupted turn: no assistant message exists for existing user message", async () => {
    const existingUserMsg = makeMessage({
      id: "msg-u1",
      createdAt: new Date("2026-01-01T10:00:00Z"),
    });

    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(existingUserMsg);
    vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(null);
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([
      makeMessage({ role: "user", content: "Hello" }),
    ]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant" }),
    );

    const result = await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(repo.appendUserMessage).not.toHaveBeenCalled();
    expect(provider.generateTurn).toHaveBeenCalledOnce();
    expect(result.assistantMessage).toBe("What experience do you have?");
  });

  it("persists assistant message even when proposal is structurally invalid (AC-7, INV-12)", async () => {
    provider = createMockProvider({
      assistantMessage: "Interesting.",
      proposal: { unknownField: true },
    });
    service = new ConversationService({ repository: repo, provider, promptVersion: "1.0.0" });

    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant" }),
    );

    const result = await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(result.assistantMessage).toBe("Interesting.");
    expect(repo.persistTurn).toHaveBeenCalledOnce();
  });

  it("throws ProviderError when provider fails", async () => {
    provider = createMockProvider();
    vi.mocked(provider.generateTurn).mockRejectedValue(new Error("network error"));
    service = new ConversationService({ repository: repo, provider, promptVersion: "1.0.0" });

    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);

    await expect(
      service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      }),
    ).rejects.toThrow(ProviderError);

    expect(repo.persistTurn).not.toHaveBeenCalled();
  });

  it("throws ProviderError for malformed LLM response (no assistant message)", async () => {
    provider = { generateTurn: vi.fn().mockResolvedValue({}) };
    service = new ConversationService({ repository: repo, provider, promptVersion: "1.0.0" });

    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);

    await expect(
      service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      }),
    ).rejects.toThrow(ProviderError);

    expect(repo.persistTurn).not.toHaveBeenCalled();
  });

  it("closes conversation on closing turn (AC-14)", async () => {
    const state: ConversationState = {
      phase: "wrap_up",
      topics: [],
      currentTopicId: null,
      questionCount: 8,
    };
    vi.mocked(repo.findById).mockResolvedValue(makeConversation({ state }));
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant" }),
    );

    const result = await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Thank you.",
    });

    expect(result.closing).toBe(true);
    expect(result.status).toBe("closed");
    expect(repo.persistTurn).toHaveBeenCalledWith(
      expect.objectContaining({ close: true }),
    );
  });

  it("increments stateVersion on each turn", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ stateVersion: 5 }),
    );
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant" }),
    );

    await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(repo.persistTurn).toHaveBeenCalledWith(
      expect.objectContaining({ stateVersion: 6 }),
    );
  });

  it("passes last 12 messages as context to provider (AC-17)", async () => {
    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue(
      Array.from({ length: 12 }, (_, i) =>
        makeMessage({ id: `msg-${i}`, content: `Message ${i}` }),
      ),
    );
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant" }),
    );

    await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(repo.loadRecentMessages).toHaveBeenCalledWith("conv-1", 12);
    expect(provider.generateTurn).toHaveBeenCalledWith(
      expect.objectContaining({
        messages: expect.arrayContaining([
          expect.objectContaining({ content: "Message 0" }),
        ]),
      }),
    );
  });

  it("applies valid proposal operations through merge", async () => {
    provider = createMockProvider({
      assistantMessage: "Tell me about your mobile app work.",
      proposal: {
        newTopics: [{ label: "mobile app" }],
        phase: "experience",
      },
    });
    service = new ConversationService({ repository: repo, provider, promptVersion: "1.0.0" });

    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ phase: "intro" } as any),
    );
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant" }),
    );

    const result = await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "I built a mobile app.",
    });

    expect(result.state.phase).toBe("experience");
    expect(result.state.topics).toHaveLength(1);
    expect(result.state.topics[0].label).toBe("mobile app");
  });

  describe("concurrent idempotency race (Issue 4)", () => {
    it("recovers from P2002 unique constraint violation and replays completed turn", async () => {
      const existingAssistant = makeMessage({
        id: "msg-a1",
        role: "assistant",
        content: "Race winner answer",
      });

      vi.mocked(repo.findById).mockResolvedValue(makeConversation());
      // First check: not found
      vi.mocked(repo.findUserMessageByClientTurnId)
        .mockResolvedValueOnce(null) // initial check
        .mockResolvedValueOnce(       // re-fetch after P2002
          makeMessage({ createdAt: new Date("2026-01-01T10:00:00Z") }),
        );
      // Append fails with P2002
      vi.mocked(repo.appendUserMessage).mockRejectedValue(
        Object.assign(new Error("Unique constraint failed"), { code: "P2002" }),
      );
      vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(existingAssistant);

      const result = await service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      });

      expect(result.assistantMessage).toBe("Race winner answer");
      expect(provider.generateTurn).not.toHaveBeenCalled();
    });

    it("recovers from P2002 and resumes interrupted turn (no assistant yet)", async () => {
      vi.mocked(repo.findById).mockResolvedValue(makeConversation());
      vi.mocked(repo.findUserMessageByClientTurnId)
        .mockResolvedValueOnce(null) // initial check
        .mockResolvedValueOnce(       // re-fetch after P2002
          makeMessage({ createdAt: new Date("2026-01-01T10:00:00Z") }),
        );
      vi.mocked(repo.appendUserMessage).mockRejectedValue(
        Object.assign(new Error("Unique constraint failed"), { code: "P2002" }),
      );
      vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(null);
      vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
      vi.mocked(repo.persistTurn).mockResolvedValue(
        makeMessage({ role: "assistant" }),
      );

      const result = await service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      });

      expect(provider.generateTurn).toHaveBeenCalledOnce();
      expect(result.assistantMessage).toBe("What experience do you have?");
    });

    it("rethrows non-P2002 errors from appendUserMessage", async () => {
      vi.mocked(repo.findById).mockResolvedValue(makeConversation());
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
      vi.mocked(repo.appendUserMessage).mockRejectedValue(
        Object.assign(new Error("Connection lost"), { code: "P1001" }),
      );

      await expect(
        service.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-1",
          message: "Hello",
        }),
      ).rejects.toThrow("Connection lost");
    });
  });

  describe("idempotent replay closing accuracy (Issue 3)", () => {
    it("replay for active conversation reports closing: false", async () => {
      const userCreatedAt = new Date("2026-01-01T10:00:00Z");
      vi.mocked(repo.findById).mockResolvedValue(
        makeConversation({ status: "active" }),
      );
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(
        makeMessage({ createdAt: userCreatedAt }),
      );
      vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(
        makeMessage({ role: "assistant", content: "Earlier answer" }),
      );

      const result = await service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      });

      expect(result.closing).toBe(false);
      expect(result.status).toBe("active");
    });
  });

  describe("closing-turn replay against a closed conversation (M21 §7.B)", () => {
    const closingUserMessage = makeMessage({
      id: "msg-u-closing",
      createdAt: new Date("2026-01-01T10:00:00Z"),
    });
    const closingReply = makeMessage({
      id: "msg-a-closing",
      role: "assistant",
      content: "Thank you, that concludes the interview.",
    });

    function mockClosedWithPersistedClosingTurn() {
      vi.mocked(repo.findById).mockResolvedValue(
        makeConversation({
          status: "closed",
          state: {
            phase: "wrap_up",
            topics: [],
            currentTopicId: null,
            questionCount: 8,
          },
        }),
      );
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(
        closingUserMessage,
      );
      vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(closingReply);
    }

    it("replays the persisted final reply for the same clientTurnId (AC-01)", async () => {
      mockClosedWithPersistedClosingTurn();

      const result = await service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      });

      expect(result).toMatchObject({
        assistantMessage: "Thank you, that concludes the interview.",
        status: "closed",
        closing: true,
      });
      expect(result.state.questionCount).toBe(8);
      expect(provider.generateTurn).not.toHaveBeenCalled();
    });

    it("replay writes nothing (AC-02)", async () => {
      mockClosedWithPersistedClosingTurn();

      await service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      });

      expect(repo.appendUserMessage).not.toHaveBeenCalled();
      expect(repo.persistTurn).not.toHaveBeenCalled();
    });

    it("still rejects a new clientTurnId on a closed conversation before any write (AC-03)", async () => {
      vi.mocked(repo.findById).mockResolvedValue(
        makeConversation({ status: "closed" }),
      );
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);

      await expect(
        service.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-new",
          message: "Hello again",
        }),
      ).rejects.toThrow(ConversationClosedError);

      expect(repo.appendUserMessage).not.toHaveBeenCalled();
      expect(repo.persistTurn).not.toHaveBeenCalled();
      expect(provider.generateTurn).not.toHaveBeenCalled();
    });

    it("rejects when the recorded user message has no persisted reply, without appending", async () => {
      vi.mocked(repo.findById).mockResolvedValue(
        makeConversation({ status: "closed" }),
      );
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(
        closingUserMessage,
      );
      vi.mocked(repo.findFirstAssistantAfter).mockResolvedValue(null);

      await expect(
        service.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-1",
          message: "Hello",
        }),
      ).rejects.toThrow(ConversationClosedError);

      expect(repo.appendUserMessage).not.toHaveBeenCalled();
      expect(repo.persistTurn).not.toHaveBeenCalled();
    });

    it("probe performs no write before the closed rejection", async () => {
      vi.mocked(repo.findById).mockResolvedValue(
        makeConversation({ status: "closed" }),
      );
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);

      await expect(
        service.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-new",
          message: "Hello",
        }),
      ).rejects.toThrow(ConversationClosedError);

      expect(repo.findUserMessageByClientTurnId).toHaveBeenCalledWith(
        "conv-1",
        "turn-new",
      );
      expect(repo.appendUserMessage).not.toHaveBeenCalled();
    });
  });

  describe("one active turn per conversation (MVP concurrency rule)", () => {
    function setupSlowProvider(delayMs: number) {
      let resolveProvider: (() => void) | undefined;
      const providerGate = new Promise<void>((resolve) => {
        resolveProvider = resolve;
      });

      const slowProvider: LLMProvider = {
        generateTurn: vi.fn().mockImplementation(async () => {
          await new Promise((r) => setTimeout(r, delayMs));
          await providerGate;
          return { assistantMessage: "Slow response" };
        }),
      };

      return { slowProvider, releaseProvider: () => resolveProvider!() };
    }

    function setupHappyPathMocks() {
      vi.mocked(repo.findById).mockResolvedValue(makeConversation());
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
      vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
      vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
      vi.mocked(repo.persistTurn).mockResolvedValue(
        makeMessage({ role: "assistant" }),
      );
    }

    it("rejects a second request with the SAME clientTurnId while first is processing", async () => {
      const { slowProvider, releaseProvider } = setupSlowProvider(10);
      const svc = new ConversationService({
        repository: repo,
        provider: slowProvider,
      });
      setupHappyPathMocks();

      const p1 = svc.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "Hello",
      });

      // p1 is now in-flight; the guard is active
      await expect(
        svc.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-1",
          message: "Hello",
        }),
      ).rejects.toThrow(TurnInProgressError);

      releaseProvider();
      await p1;
    });

    it("rejects a second request with a DIFFERENT clientTurnId while first is processing", async () => {
      const { slowProvider, releaseProvider } = setupSlowProvider(10);
      const svc = new ConversationService({
        repository: repo,
        provider: slowProvider,
      });
      setupHappyPathMocks();

      const p1 = svc.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-A",
        message: "Hello",
      });

      await expect(
        svc.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-B",
          message: "Different message",
        }),
      ).rejects.toThrow(TurnInProgressError);

      releaseProvider();
      await p1;
    });

    it("does not call the LLM or append a second user message for the rejected request", async () => {
      const { slowProvider, releaseProvider } = setupSlowProvider(50);
      const svc = new ConversationService({
        repository: repo,
        provider: slowProvider,
      });
      setupHappyPathMocks();

      const p1 = svc.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-A",
        message: "Hello",
      });

      // Yield to let p1 progress past its awaits and reach the provider
      await new Promise((r) => setTimeout(r, 20));

      await expect(
        svc.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-B",
          message: "Another",
        }),
      ).rejects.toThrow(TurnInProgressError);

      // Only one LLM call and one user message append (from p1)
      expect(slowProvider.generateTurn).toHaveBeenCalledTimes(1);
      expect(repo.appendUserMessage).toHaveBeenCalledTimes(1);

      releaseProvider();
      await p1;
    });

    it("allows a new turn after the first turn completes", async () => {
      const fastProvider: LLMProvider = {
        generateTurn: vi.fn().mockResolvedValue({
          assistantMessage: "Response",
        }),
      };
      const svc = new ConversationService({
        repository: repo,
        provider: fastProvider,
      });
      setupHappyPathMocks();

      // First turn completes
      await svc.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "First",
      });

      // Second turn with different clientTurnId should succeed
      const result = await svc.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-2",
        message: "Second",
      });

      expect(result.assistantMessage).toBe("Response");
      expect(fastProvider.generateTurn).toHaveBeenCalledTimes(2);
    });

    it("releases the guard even when the turn fails", async () => {
      const provider: LLMProvider = {
        generateTurn: vi
          .fn()
          .mockRejectedValueOnce(new Error("LLM crash"))
          .mockResolvedValueOnce({ assistantMessage: "Recovered" }),
      };
      const svc = new ConversationService({
        repository: repo,
        provider,
      });
      setupHappyPathMocks();

      await expect(
        svc.processTurn({
          conversationId: "conv-1",
          clientTurnId: "turn-1",
          message: "Hello",
        }),
      ).rejects.toThrow(ProviderError);

      // Guard released — new turn on the same service instance should succeed
      vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
      vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());

      const result = await svc.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-2",
        message: "After failure",
      });

      expect(result.assistantMessage).toBe("Recovered");
    });

    it("concurrent requests: only one succeeds, others rejected (true concurrency)", async () => {
      const { slowProvider, releaseProvider } = setupSlowProvider(50);
      const svc = new ConversationService({
        repository: repo,
        provider: slowProvider,
      });
      setupHappyPathMocks();

      // Launch 5 concurrent requests simultaneously
      const requests = Array.from({ length: 5 }, (_, i) =>
        svc.processTurn({
          conversationId: "conv-1",
          clientTurnId: `turn-${i}`,
          message: `Message ${i}`,
        }),
      );

      // Release the provider gate after giving requests time to start
      setTimeout(() => releaseProvider(), 20);

      const results = await Promise.allSettled(requests);

      const fulfilled = results.filter((r) => r.status === "fulfilled");
      const rejected = results.filter((r) => r.status === "rejected");

      // Exactly one succeeded, the rest got TurnInProgressError
      expect(fulfilled).toHaveLength(1);
      expect(rejected).toHaveLength(4);

      for (const r of rejected) {
        expect((r as PromiseRejectedResult).reason).toBeInstanceOf(
          TurnInProgressError,
        );
      }

      // Only one LLM call was made
      expect(slowProvider.generateTurn).toHaveBeenCalledTimes(1);
    });
  });
});

/**
 * M20 T4 — the Practice Context read inside the turn (Contract v0.1 §10, §13.1,
 * §16.4, §17, §19.4).
 *
 * `systemPrompt` is the ONLY channel M20 can affect (§18.6), so every assertion
 * about enrichment is made against the provider payload and against the persisted
 * row — never against internal state.
 */
describe("ConversationService — M20 practice context read", () => {
  const PROFILE_ID = "prof-1";

  function practiceItem(
    overrides: Partial<PracticeContextItem> = {},
  ): PracticeContextItem {
    return {
      dimension: "english",
      focus: "grammar",
      observation: "Tense agreement slips in longer answers.",
      practiceCue: "Retell one accomplishment entirely in present perfect.",
      ...overrides,
    };
  }

  const experienceProfile = {
    id: PROFILE_ID,
    items: [
      {
        id: "exp-1",
        title: "Mobile App",
        organization: null,
        role: null,
        description: "A note taking app.",
        skills: ["TypeScript"],
        position: 0,
      },
    ],
    createdAt: new Date("2026-01-01T00:00:00Z"),
    updatedAt: new Date("2026-01-02T00:00:00Z"),
  };

  interface SetupOptions {
    phase?: ConversationState["phase"];
    questionCount?: number;
    experienceProfileId?: string | null;
    practiceItems?: PracticeContextItem[];
    withPracticePort?: boolean;
    withExperienceReader?: boolean;
    assistantMessage?: string;
    proposal?: unknown;
  }

  function setup(options: SetupOptions = {}) {
    const {
      phase = "deep_dive",
      questionCount = 3,
      experienceProfileId = PROFILE_ID,
      practiceItems = [],
      withPracticePort = true,
      withExperienceReader = false,
      assistantMessage = "What did you build?",
      proposal = null,
    } = options;

    const repo = createMockRepo();
    const provider = createMockProvider({ assistantMessage, proposal });
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({
        experienceProfileId,
        state: {
          phase,
          topics: [{ id: "t1", label: "mobile app", covered: false }],
          currentTopicId: "t1",
          questionCount,
        },
      }),
    );
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([
      makeMessage({ role: "user", content: "I built a mobile app." }),
    ]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant", content: assistantMessage }),
    );

    const findForExperienceProfile = vi
      .fn<PracticeContextReader["findForExperienceProfile"]>()
      .mockResolvedValue(practiceItems);
    const practiceContext = withPracticePort ? { findForExperienceProfile } : undefined;

    const experienceProfiles: ExperienceProfileReader | undefined =
      withExperienceReader
        ? { findById: vi.fn().mockResolvedValue(experienceProfile) }
        : undefined;

    const service = new ConversationService({
      repository: repo,
      provider,
      experienceProfiles,
      practiceContext,
    });

    const run = () =>
      service.processTurn({
        conversationId: "conv-1",
        clientTurnId: "turn-1",
        message: "I built a mobile app.",
      });

    const providerInput = () =>
      vi.mocked(provider.generateTurn).mock.calls[0][0] as {
        systemPrompt: string;
        state: ConversationState;
        messages: unknown;
      };

    return {
      repo,
      provider,
      service,
      run,
      read: findForExperienceProfile,
      providerInput,
      systemPrompt: () => providerInput().systemPrompt,
      persisted: () => vi.mocked(repo.persistTurn).mock.calls[0][0],
    };
  }

  it("reads through the port with the conversation's own experience profile id (AC-1, AC-29)", async () => {
    const ctx = setup({ practiceItems: [practiceItem()] });

    await ctx.run();

    expect(ctx.read).toHaveBeenCalledTimes(1);
    expect(ctx.read).toHaveBeenCalledWith(PROFILE_ID);
  });

  it("never reads for a conversation without an experience profile (AC-2)", async () => {
    const ctx = setup({ experienceProfileId: null, practiceItems: [practiceItem()] });

    const result = await ctx.run();

    expect(ctx.read).not.toHaveBeenCalled();
    expect(ctx.systemPrompt()).not.toContain("PRACTICE CONTEXT");
    expect(result.status).toBe("active");
  });

  it("behaves exactly as before when the port is not wired", async () => {
    const wired = setup({ withPracticePort: false, practiceItems: [practiceItem()] });
    const result = await wired.run();

    expect(wired.systemPrompt()).not.toContain("PRACTICE CONTEXT");
    expect(result.assistantMessage).toBe("What did you build?");
    expect(wired.repo.persistTurn).toHaveBeenCalledOnce();
  });

  it("does not read on a wrap_up turn, so closing never competes with hints (§16.4)", async () => {
    const ctx = setup({
      phase: "wrap_up",
      questionCount: 8,
      practiceItems: [practiceItem()],
      assistantMessage: "Thank you, that concludes our interview.",
    });

    const result = await ctx.run();

    expect(ctx.read).not.toHaveBeenCalled();
    expect(ctx.systemPrompt()).not.toContain("PRACTICE CONTEXT");
    expect(ctx.systemPrompt()).toContain("wrap-up phase");
    expect(result.closing).toBe(true);
    expect(result.status).toBe("closed");
  });

  it("reads on the intro phase — the omission rule is wrap_up only (§16.4)", async () => {
    const ctx = setup({
      phase: "intro",
      questionCount: 0,
      practiceItems: [practiceItem()],
    });

    await ctx.run();

    expect(ctx.read).toHaveBeenCalledWith(PROFILE_ID);
    expect(ctx.systemPrompt()).toContain("PRACTICE CONTEXT (BEGIN)");
  });

  it("leaves the prompt byte-identical when the selection is empty (§14.8, AC-13)", async () => {
    const withEmpty = setup({ practiceItems: [] });
    const withoutPort = setup({ withPracticePort: false });

    await withEmpty.run();
    await withoutPort.run();

    expect(withEmpty.systemPrompt()).toBe(withoutPort.systemPrompt());
    expect(withEmpty.systemPrompt()).not.toContain("PRACTICE CONTEXT");
  });

  it("appends the block to the prompt handed to the provider, after the experience block (AC-6, AC-10)", async () => {
    const ctx = setup({
      withExperienceReader: true,
      practiceItems: [
        practiceItem(),
        practiceItem({
          dimension: "clarity",
          focus: "hard_to_follow",
          observation: "Signposting is thin.",
          practiceCue: "Name the point before the detail.",
        }),
      ],
    });

    await ctx.run();
    const prompt = ctx.systemPrompt();

    expect(prompt).toContain("===== PRACTICE CONTEXT (BEGIN) =====");
    expect(prompt).toContain("===== PRACTICE CONTEXT (END) =====");
    expect(prompt).toContain("- Dimension: english");
    expect(prompt).toContain("  Practise: Name the point before the detail.");
    expect(prompt.indexOf("CANDIDATE EXPERIENCE PROFILE (END)")).toBeLessThan(
      prompt.indexOf("PRACTICE CONTEXT (BEGIN)"),
    );
  });

  it("keeps the provider input surface unchanged: exactly systemPrompt, state, messages (§18.6, AC-6, AC-8)", async () => {
    const ctx = setup({ practiceItems: [practiceItem()] });

    await ctx.run();
    const input = ctx.providerInput();

    expect(Object.keys(input).sort()).toEqual(["messages", "state", "systemPrompt"]);
    expect(Object.keys(input.state).sort()).toEqual([
      "currentTopicId",
      "phase",
      "questionCount",
      "topics",
    ]);
    // Nothing from the derivation rows beyond the four allowed fields, and no
    // identifier or previous-conversation content in the prompt at all.
    for (const forbidden of [
      "conv-1",
      "msg-1",
      "turn-1",
      "prof-1",
      "exp-1",
      "derivationId",
      "practiceTrackId",
      "feedbackId",
      "answerMessageId",
      "questionMessageId",
      "feedbackItemIndex",
    ]) {
      expect(input.systemPrompt).not.toContain(forbidden);
    }
  });

  it("changes nothing else: identical state, provider payload and persisted rows with and without context (§17.1, AC-28)", async () => {
    const enriched = setup({ practiceItems: [practiceItem()] });
    const plain = setup({ practiceItems: [] });

    const enrichedResult = await enriched.run();
    const plainResult = await plain.run();

    const stripPrompt = (x: { systemPrompt: string }) => {
      const { systemPrompt: _omit, ...rest } = x;
      return rest;
    };

    expect(stripPrompt(enriched.providerInput())).toEqual(
      stripPrompt(plain.providerInput()),
    );
    expect(enriched.persisted()).toEqual(plain.persisted());
    expect(enrichedResult).toEqual(plainResult);
    // The read adds no repository interaction and no second write.
    expect(enriched.repo.persistTurn).toHaveBeenCalledOnce();
    expect(vi.mocked(enriched.repo.appendUserMessage)).toHaveBeenCalledOnce();
    expect(vi.mocked(enriched.repo.loadRecentMessages)).toHaveBeenCalledOnce();
    const interaction = (r: ConversationRepository) =>
      Object.fromEntries(
        Object.entries(r).map(([name, delegate]) => [
          name,
          (delegate as { mock: { calls: unknown[] } }).mock.calls.length,
        ]),
      );
    expect(interaction(enriched.repo)).toEqual(interaction(plain.repo));
  });

  it("an empty result from a failing read cannot fail the turn (§17.2, AC-14)", async () => {
    // The port contract is "never throws": §17.3 puts the catch inside the
    // reader, which resolves to [] after logging. That swallowed-failure shape
    // is proven in the derivation module's own reader test; what is pinned here is the
    // conversation-side consequence — the turn completes with the plain prompt.
    const ctx = setup({ practiceItems: [] });

    const result = await ctx.run();

    expect(result.assistantMessage).toBe("What did you build?");
    expect(result.status).toBe("active");
    expect(ctx.systemPrompt()).not.toContain("PRACTICE CONTEXT");
    expect(ctx.repo.persistTurn).toHaveBeenCalledOnce();
  });

  it("performs no derivation read on an idempotent replay (§17.6)", async () => {
    const ctx = setup({ practiceItems: [practiceItem()] });
    vi.mocked(ctx.repo.findUserMessageByClientTurnId).mockResolvedValue(
      makeMessage({ createdAt: new Date("2026-01-01T10:00:00Z") }),
    );
    vi.mocked(ctx.repo.findFirstAssistantAfter).mockResolvedValue(
      makeMessage({ role: "assistant", content: "Earlier answer" }),
    );

    const result = await ctx.run();

    expect(result.assistantMessage).toBe("Earlier answer");
    expect(ctx.read).not.toHaveBeenCalled();
    expect(ctx.provider.generateTurn).not.toHaveBeenCalled();
  });

  it("several question marks in one message still add exactly +1 while context is present (§19.4, AC-17)", async () => {
    const ctx = setup({
      practiceItems: [practiceItem()],
      assistantMessage: "Was it a team effort? And what did you own? And how did it ship?",
    });

    const result = await ctx.run();

    expect(ctx.systemPrompt()).toContain("PRACTICE CONTEXT (BEGIN)");
    expect(result.state.questionCount).toBe(4);
    expect(result.state.phase).toBe("deep_dive");
  });

  it("still forces wrap_up at MAX_QUESTIONS with context present (AC-17)", async () => {
    const ctx = setup({
      questionCount: 7,
      practiceItems: [practiceItem()],
      assistantMessage: "And what was the hardest part?",
    });

    const result = await ctx.run();

    expect(result.state.questionCount).toBe(8);
    expect(result.state.phase).toBe("wrap_up");
  });

  it("persists the bumped interview prompt version for new messages (AC-18)", async () => {
    const ctx = setup({ practiceItems: [practiceItem()] });

    await ctx.run();

    expect(PROMPT_VERSION).toBe("2.2.0");
    expect(ctx.persisted()).toMatchObject({ promptVersion: "2.2.0" });
  });

  it("an explicit promptVersion override still wins — the version stays a single default (§15.2)", async () => {
    const repo = createMockRepo();
    const provider = createMockProvider();
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ experienceProfileId: PROFILE_ID }),
    );
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant" }),
    );
    const service = new ConversationService({
      repository: repo,
      provider,
      promptVersion: "2.0.0",
      practiceContext: {
        findForExperienceProfile: vi.fn().mockResolvedValue([practiceItem()]),
      },
    });

    await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hello",
    });

    expect(vi.mocked(repo.persistTurn).mock.calls[0][0]).toMatchObject({
      promptVersion: "2.0.0",
    });
  });
});

describe("ConversationService.generateOpening", () => {
  let repo: ConversationRepository;
  let provider: LLMProvider;
  let service: ConversationService;

  beforeEach(() => {
    repo = createMockRepo();
    (repo as unknown as Record<string, unknown>)["loadAllMessages"] = vi.fn().mockResolvedValue([]);
    provider = createMockProvider({
      assistantMessage: "Hi! Tell me about your experience.",
    });
    service = new ConversationService({
      repository: repo,
      provider,
      promptVersion: "1.0.0",
    });
  });

  it("throws ConversationNotFoundError for unknown conversations", async () => {
    vi.mocked(repo.findById).mockResolvedValue(null);
    await expect(service.generateOpening("missing")).rejects.toThrow(
      ConversationNotFoundError,
    );
  });

  it("generates with an empty transcript and persists the assistant message", async () => {
    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    const result = await service.generateOpening("conv-1");
    expect(result.assistantMessage).toBe("Hi! Tell me about your experience.");
    expect(result.status).toBe("active");
    expect(vi.mocked(provider.generateTurn).mock.calls[0][0]).toMatchObject({
      messages: [],
    });
    expect(vi.mocked(repo.persistTurn)).toHaveBeenCalledOnce();
    expect(vi.mocked(repo.persistTurn).mock.calls[0][0]).toMatchObject({
      conversationId: "conv-1",
      assistantMessage: "Hi! Tell me about your experience.",
      close: false,
    });
  });

  it("replays the existing opening instead of generating again", async () => {
    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    (repo.loadAllMessages as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      makeMessage({ role: "assistant", content: "Welcome back." }),
    ]);
    const result = await service.generateOpening("conv-1");
    expect(result.assistantMessage).toBe("Welcome back.");
    expect(provider.generateTurn).not.toHaveBeenCalled();
    expect(repo.persistTurn).not.toHaveBeenCalled();
  });

  it("throws OpeningAlreadyTakenError once user turns exist", async () => {
    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    (repo.loadAllMessages as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      makeMessage({ role: "assistant", content: "Hi." }),
      makeMessage({ role: "user", content: "Hello" }),
    ]);
    await expect(service.generateOpening("conv-1")).rejects.toThrow(
      OpeningAlreadyTakenError,
    );
  });

  it("maps provider failure to ProviderError", async () => {
    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(provider.generateTurn).mockRejectedValue(new Error("llm down"));
    await expect(service.generateOpening("conv-1")).rejects.toThrow(
      ProviderError,
    );
  });
});
