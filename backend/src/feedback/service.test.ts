import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  FeedbackService,
  ConversationNotFoundError,
  ConversationNotClosedError,
  FeedbackInProgressError,
  FeedbackNotFoundError,
} from "./service.js";
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
function ts(offsetSec: number): Date {
  return new Date(T0 + offsetSec * 1000);
}

function makeConversation(
  overrides: Partial<ConversationRecord> = {},
): ConversationRecord {
  return {
    id: "conv-1",
    status: "closed",
    state: { phase: "wrap_up", topics: [], currentTopicId: null, questionCount: 8 },
    stateVersion: 9,
    experienceProfileId: null,
    createdAt: ts(0),
    updatedAt: ts(100),
    ...overrides,
  };
}

// A small realistic transcript: assistant question then user answer, twice.
function defaultMessages(): MessageRecord[] {
  return [
    msg("a1", "assistant", "Tell me about a project.", 0),
    msg("u1", "user", "I built a mobile app.", 1),
    msg("a2", "assistant", "What was hard about it?", 2),
    msg("u2", "user", "State management was hard.", 3),
  ];
}
function msg(
  id: string,
  role: "system" | "user" | "assistant",
  content: string,
  offsetSec: number,
): MessageRecord {
  return {
    id,
    conversationId: "conv-1",
    role,
    content,
    promptVersion: role === "assistant" ? "2.0.0" : null,
    clientTurnId: role === "user" ? `t-${id}` : null,
    createdAt: ts(offsetSec),
  };
}

function makeConvRepo(
  conversation: ConversationRecord | null,
  messages: MessageRecord[] = defaultMessages(),
): ConversationRepository {
  return {
    findById: vi.fn().mockResolvedValue(conversation),
    loadAllMessages: vi.fn().mockResolvedValue(messages),
  } as unknown as ConversationRepository;
}

/** In-memory feedback repo enforcing the 1:1 unique invariant. */
class InMemoryFeedbackRepo {
  private store = new Map<string, FeedbackArtifact>();
  createCalls = 0;

  async findByConversationId(id: string): Promise<FeedbackArtifact | null> {
    return this.store.get(id) ?? null;
  }
  async create(input: PersistFeedbackInput): Promise<FeedbackArtifact> {
    this.createCalls += 1;
    if (this.store.has(input.conversationId)) {
      throw Object.assign(new Error("unique"), { code: "P2002" });
    }
    const artifact: FeedbackArtifact = {
      conversationId: input.conversationId,
      promptVersion: input.promptVersion,
      overall: input.overall,
      answerItems: input.answerItems,
      professionalCommunication: input.professionalCommunication,
      createdAt: ts(200),
    };
    this.store.set(input.conversationId, artifact);
    return artifact;
  }
  seed(artifact: FeedbackArtifact) {
    this.store.set(artifact.conversationId, artifact);
  }
}

function makeProvider(output: FeedbackOutputRaw): FeedbackProvider {
  return { generateFeedback: vi.fn().mockResolvedValue(output) };
}

function asFeedbackRepo(r: InMemoryFeedbackRepo): FeedbackRepository {
  return r as unknown as FeedbackRepository;
}

const VALID_OUTPUT: FeedbackOutputRaw = {
  overall: "You gave concrete examples; work on outcomes.",
  answerItems: [
    {
      answerMessageId: "u1",
      questionMessageId: "a1",
      whatWorked: "Named a real project.",
      couldImprove: "Add the impact.",
    },
  ],
};

describe("FeedbackService.generateFeedback", () => {
  let feedbackRepo: InMemoryFeedbackRepo;

  beforeEach(() => {
    feedbackRepo = new InMemoryFeedbackRepo();
  });

  it("throws ConversationNotFound when conversation is missing", async () => {
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(null),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider: makeProvider(VALID_OUTPUT),
    });
    await expect(service.generateFeedback("missing")).rejects.toThrow(
      ConversationNotFoundError,
    );
  });

  it("throws ConversationNotClosed when conversation is not closed", async () => {
    const provider = makeProvider(VALID_OUTPUT);
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation({ status: "active" })),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider,
    });
    await expect(service.generateFeedback("conv-1")).rejects.toThrow(
      ConversationNotClosedError,
    );
    expect(provider.generateFeedback).not.toHaveBeenCalled();
  });

  it("generates and persists feedback for a closed conversation (created=true)", async () => {
    const provider = makeProvider(VALID_OUTPUT);
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider,
      promptVersion: "feedback-1.0.0",
    });
    const result = await service.generateFeedback("conv-1");
    expect(result.created).toBe(true);
    expect(result.feedback.overall).toContain("concrete");
    expect(result.feedback.promptVersion).toBe("feedback-1.0.0");
    expect(result.feedback.answerItems[0].answerMessageId).toBe("u1");
    expect(result.feedback.answerItems[0].questionMessageId).toBe("a1");
    // M13: derived questionText resolved from the transcript (a1 content).
    expect(result.feedback.answerItems[0].questionText).toBe(
      "Tell me about a project.",
    );
    expect(provider.generateFeedback).toHaveBeenCalledOnce();
  });

  it("returns existing feedback WITHOUT calling the LLM (created=false)", async () => {
    feedbackRepo.seed({
      conversationId: "conv-1",
      promptVersion: "feedback-1.0.0",
      overall: "Prior feedback.",
      answerItems: [],
      professionalCommunication: null,
      createdAt: ts(200),
    });
    const provider = makeProvider(VALID_OUTPUT);
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider,
    });
    const result = await service.generateFeedback("conv-1");
    expect(result.created).toBe(false);
    expect(result.feedback.overall).toBe("Prior feedback.");
    expect(provider.generateFeedback).not.toHaveBeenCalled();
  });

  it("provider failure persists no feedback and can be retried", async () => {
    const failing: FeedbackProvider = {
      generateFeedback: vi
        .fn()
        .mockRejectedValueOnce(new ProviderError("boom"))
        .mockResolvedValueOnce(VALID_OUTPUT),
    };
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider: failing,
    });

    await expect(service.generateFeedback("conv-1")).rejects.toThrow(ProviderError);
    expect(await feedbackRepo.findByConversationId("conv-1")).toBeNull();

    // Retry succeeds.
    const result = await service.generateFeedback("conv-1");
    expect(result.created).toBe(true);
    expect(await feedbackRepo.findByConversationId("conv-1")).not.toBeNull();
  });

  it("propagates provider timeout (mapped to 504 at the route)", async () => {
    const provider: FeedbackProvider = {
      generateFeedback: vi.fn().mockRejectedValue(new ProviderTimeoutError("t")),
    };
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider,
    });
    await expect(service.generateFeedback("conv-1")).rejects.toThrow(
      ProviderTimeoutError,
    );
  });

  describe("LLM message-reference validation", () => {
    it("drops an item whose answerMessageId does not exist", async () => {
      const provider = makeProvider({
        overall: "ok",
        answerItems: [{ answerMessageId: "nope", whatWorked: "x" }],
      });
      const service = new FeedbackService({
        conversationRepository: makeConvRepo(makeConversation()),
        feedbackRepository: asFeedbackRepo(feedbackRepo),
        provider,
      });
      const result = await service.generateFeedback("conv-1");
      expect(result.feedback.answerItems).toHaveLength(0);
    });

    it("drops an item whose answerMessageId has the wrong role (assistant)", async () => {
      const provider = makeProvider({
        overall: "ok",
        answerItems: [{ answerMessageId: "a1", whatWorked: "x" }], // a1 is assistant
      });
      const service = new FeedbackService({
        conversationRepository: makeConvRepo(makeConversation()),
        feedbackRepository: asFeedbackRepo(feedbackRepo),
        provider,
      });
      const result = await service.generateFeedback("conv-1");
      expect(result.feedback.answerItems).toHaveLength(0);
    });

    it("nulls a questionMessageId with the wrong role but keeps the item", async () => {
      const provider = makeProvider({
        overall: "ok",
        answerItems: [{ answerMessageId: "u1", questionMessageId: "u2" }], // u2 is user
      });
      const service = new FeedbackService({
        conversationRepository: makeConvRepo(makeConversation()),
        feedbackRepository: asFeedbackRepo(feedbackRepo),
        provider,
      });
      const result = await service.generateFeedback("conv-1");
      expect(result.feedback.answerItems).toHaveLength(1);
      expect(result.feedback.answerItems[0].questionMessageId).toBeNull();
    });

    it("nulls a questionMessageId that does not precede the answer", async () => {
      // a2 (offset 2) comes AFTER u1 (offset 1), so it is not a valid preceding question for u1.
      const provider = makeProvider({
        overall: "ok",
        answerItems: [{ answerMessageId: "u1", questionMessageId: "a2" }],
      });
      const service = new FeedbackService({
        conversationRepository: makeConvRepo(makeConversation()),
        feedbackRepository: asFeedbackRepo(feedbackRepo),
        provider,
      });
      const result = await service.generateFeedback("conv-1");
      expect(result.feedback.answerItems[0].questionMessageId).toBeNull();
    });

    it("accepts a valid preceding question reference", async () => {
      const provider = makeProvider({
        overall: "ok",
        answerItems: [{ answerMessageId: "u2", questionMessageId: "a2" }],
      });
      const service = new FeedbackService({
        conversationRepository: makeConvRepo(makeConversation()),
        feedbackRepository: asFeedbackRepo(feedbackRepo),
        provider,
      });
      const result = await service.generateFeedback("conv-1");
      expect(result.feedback.answerItems[0].questionMessageId).toBe("a2");
    });
  });

  it("uses the full transcript (loadAllMessages), not a window", async () => {
    const convRepo = makeConvRepo(makeConversation());
    const service = new FeedbackService({
      conversationRepository: convRepo,
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider: makeProvider(VALID_OUTPUT),
    });
    await service.generateFeedback("conv-1");
    expect(convRepo.loadAllMessages).toHaveBeenCalledWith("conv-1");
  });
});

describe("FeedbackService.getFeedback", () => {
  let feedbackRepo: InMemoryFeedbackRepo;
  beforeEach(() => {
    feedbackRepo = new InMemoryFeedbackRepo();
  });

  it("returns existing feedback", async () => {
    feedbackRepo.seed({
      conversationId: "conv-1",
      promptVersion: "feedback-1.0.0",
      overall: "Existing.",
      answerItems: [],
      professionalCommunication: null,
      createdAt: ts(200),
    });
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider: makeProvider(VALID_OUTPUT),
    });
    const fb = await service.getFeedback("conv-1");
    expect(fb.overall).toBe("Existing.");
  });

  it("throws FeedbackNotFound when absent (never generates)", async () => {
    const provider = makeProvider(VALID_OUTPUT);
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider,
    });
    await expect(service.getFeedback("conv-1")).rejects.toThrow(
      FeedbackNotFoundError,
    );
    expect(provider.generateFeedback).not.toHaveBeenCalled();
  });

  it("throws ConversationNotFound when the conversation is missing", async () => {
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(null),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider: makeProvider(VALID_OUTPUT),
    });
    await expect(service.getFeedback("missing")).rejects.toThrow(
      ConversationNotFoundError,
    );
  });

  // M13: derived questionText on the read path.
  it("attaches derived questionText resolved from the transcript", async () => {
    feedbackRepo.seed({
      conversationId: "conv-1",
      promptVersion: "feedback-1.0.0",
      overall: "Existing.",
      answerItems: [{ answerMessageId: "u1", questionMessageId: "a1" }],
      professionalCommunication: null,
      createdAt: ts(200),
    });
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider: makeProvider(VALID_OUTPUT),
    });
    const fb = await service.getFeedback("conv-1");
    expect(fb.answerItems[0].questionText).toBe("Tell me about a project.");
    // The stored artifact is not mutated (still holds only ids).
    const stored = await feedbackRepo.findByConversationId("conv-1");
    expect(
      (stored!.answerItems[0] as { questionText?: unknown }).questionText,
    ).toBeUndefined();
  });

  it("sets questionText null when the item has no question reference", async () => {
    feedbackRepo.seed({
      conversationId: "conv-1",
      promptVersion: "feedback-1.0.0",
      overall: "Existing.",
      answerItems: [{ answerMessageId: "u1", questionMessageId: null }],
      professionalCommunication: null,
      createdAt: ts(200),
    });
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider: makeProvider(VALID_OUTPUT),
    });
    const fb = await service.getFeedback("conv-1");
    // No question reference → no resolved question text (nullish). The field is
    // optional (`string | null`); the frontend treats absent/null identically.
    expect(fb.answerItems[0].questionText ?? null).toBeNull();
  });
});

describe("FeedbackService concurrency", () => {
  it("rejects an overlapping same-conversation generation with FeedbackInProgressError", async () => {
    const feedbackRepo = new InMemoryFeedbackRepo();
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const provider: FeedbackProvider = {
      generateFeedback: vi.fn().mockImplementation(async () => {
        await gate;
        return VALID_OUTPUT;
      }),
    };
    const service = new FeedbackService({
      conversationRepository: makeConvRepo(makeConversation()),
      feedbackRepository: asFeedbackRepo(feedbackRepo),
      provider,
    });

    const p1 = service.generateFeedback("conv-1");
    await expect(service.generateFeedback("conv-1")).rejects.toThrow(
      FeedbackInProgressError,
    );
    release();
    const r1 = await p1;
    expect(r1.created).toBe(true);
    expect(provider.generateFeedback).toHaveBeenCalledOnce();
  });
});
