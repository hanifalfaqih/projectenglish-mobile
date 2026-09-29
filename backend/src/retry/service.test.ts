import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  RetryService,
  RetryConversationNotFoundError,
  RetryConversationNotClosedError,
  RetryFeedbackMissingError,
  RetryItemInvalidError,
  RetryReferenceInvalidError,
  RetryNotRetryableError,
  RetryNotFoundError,
} from "./service.js";
import type {
  ConversationRepository,
  ConversationRecord,
  MessageRecord,
} from "../conversation/repository.js";
import type { FeedbackRepository } from "../feedback/repository.js";
import type {
  AnswerFeedbackItem,
  FeedbackArtifact,
} from "../feedback/schema.js";
import type { RetryRepository } from "./repository.js";
import type { RetryFeedbackProvider } from "./provider.js";
import type { RetryArtifact } from "./schema.js";

const T0 = Date.UTC(2026, 0, 1, 0, 0, 0);
function ts(offsetSec: number): Date {
  return new Date(T0 + offsetSec * 1000);
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
    promptVersion: null,
    clientTurnId: null,
    createdAt: ts(offsetSec),
  };
}

function conversation(
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

function practiceItem(overrides: Partial<AnswerFeedbackItem> = {}): AnswerFeedbackItem {
  return {
    answerMessageId: "u1",
    questionMessageId: "a1",
    practiceOpportunity: true,
    whatWorked: "Clear metric.",
    couldImprove: "Explain the challenge first.",
    tryNextTime: "Use STAR.",
    ...overrides,
  };
}

function feedbackArtifact(items: AnswerFeedbackItem[]): FeedbackArtifact {
  return {
    conversationId: "conv-1",
    promptVersion: "feedback-1.1.0",
    overall: "Good effort.",
    answerItems: items,
    professionalCommunication: null,
    createdAt: ts(200),
  };
}

function retryRow(overrides: Partial<RetryArtifact> = {}): RetryArtifact {
  return {
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
    createdAt: ts(300),
    updatedAt: ts(300),
    ...overrides,
  };
}

function setup(overrides: {
  conv?: ConversationRecord | null;
  messages?: MessageRecord[];
  feedback?: FeedbackArtifact | null;
  feedbackId?: string | null;
  currentRetry?: RetryArtifact | null;
  byKey?: RetryArtifact | null;
  providerOutput?: unknown;
  providerError?: unknown;
} = {}) {
  const messages = overrides.messages ?? [
    msg("a1", "assistant", "Tell me about a challenge?", 0),
    msg("u1", "user", "Compression was hard.", 1),
  ];
  const conversationRepository = {
    findById: vi.fn().mockResolvedValue(
      overrides.conv !== undefined ? overrides.conv : conversation(),
    ),
    loadAllMessages: vi.fn().mockResolvedValue(messages),
  } as unknown as ConversationRepository;
  const feedbackRepository = {
    findByConversationId: vi.fn().mockResolvedValue(
      overrides.feedback !== undefined
        ? overrides.feedback
        : feedbackArtifact([practiceItem()]),
    ),
    findIdByConversationId: vi.fn().mockResolvedValue(
      overrides.feedbackId !== undefined ? overrides.feedbackId : "fb-1",
    ),
  } as unknown as FeedbackRepository;
  const retryRepository = {
    findByRetryClientKey: vi.fn().mockResolvedValue(overrides.byKey ?? null),
    findCurrentByAnswer: vi.fn().mockResolvedValue(overrides.currentRetry ?? null),
    findById: vi.fn().mockImplementation(async (id: string) => retryRow({ id })),
    createSupersedingRetry: vi.fn().mockImplementation(
      async (input: {
        conversationId: string;
        feedbackId: string;
        answerMessageId: string;
        questionMessageId: string | null;
        retryClientKey: string;
        retryAnswer: string;
      }) => retryRow({ ...input, feedback: null, feedbackStatus: "pending" as const }),
    ),
    // Mirrors real update-then-read: the row read back carries the update.
    setFeedback: vi.fn().mockImplementation(
      async (
        id: string,
        feedback: RetryArtifact["feedback"],
        promptVersion: string | null,
        status: RetryArtifact["feedbackStatus"],
      ) => {
        const row = retryRow({
          id,
          feedback,
          feedbackPromptVersion: promptVersion,
          feedbackStatus: status,
          // The real update returns the stored row: preserve the answer.
          retryAnswer: baseInput.retryAnswer,
        });
        (
          retryRepository as unknown as {
            findById: ReturnType<typeof vi.fn>;
          }
        ).findById.mockResolvedValue(row);
        return row;
      },
    ),
  } as unknown as RetryRepository;
  const provider = {
    generateRetryFeedback: vi.fn().mockImplementation(async () => {
      if (overrides.providerError !== undefined) throw overrides.providerError;
      return (
        (overrides.providerOutput as Record<string, unknown> | undefined) ?? {
          overall: "Much clearer now.",
        }
      );
    }),
  } as unknown as RetryFeedbackProvider;
  const service = new RetryService({
    conversationRepository,
    feedbackRepository,
    retryRepository,
    provider,
  });
  return { conversationRepository, feedbackRepository, retryRepository, provider, service };
}

const baseInput = {
  conversationId: "conv-1",
  answerMessageId: "u1",
  questionMessageId: "a1",
  retryAnswer: "Better answer with STAR structure.",
  retryClientKey: "key-1",
};

describe("RetryService.submitRetry", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("succeeds for a practice opportunity and generates feedback", async () => {
    const { service, provider } = setup();
    const result = await service.submitRetry(baseInput);
    expect(result.created).toBe(true);
    expect(result.retry.retryAnswer).toBe(baseInput.retryAnswer);
    expect(result.retry.feedbackStatus).toBe("generated");
    expect(result.retry.feedback?.overall).toBe("Much clearer now.");
    expect(result.originalQuestion).toContain("challenge");
    expect(result.originalAnswer).toContain("Compression was hard");
    expect(provider.generateRetryFeedback).toHaveBeenCalledOnce();
  });

  it("rejects non-practice items", async () => {
    const { service, provider } = setup({
      feedback: feedbackArtifact([practiceItem({ practiceOpportunity: false })]),
    });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryNotRetryableError,
    );
    expect(provider.generateRetryFeedback).not.toHaveBeenCalled();
  });

  it("rejects legacy-normalized items without the marker", async () => {
    const item = practiceItem() as unknown as Record<string, unknown>;
    delete item["practiceOpportunity"];
    const { service } = setup({
      feedback: feedbackArtifact([item as unknown as AnswerFeedbackItem]),
    });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryNotRetryableError,
    );
  });

  it("rejects unknown conversations", async () => {
    const { service } = setup({ conv: null });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryConversationNotFoundError,
    );
  });

  it("rejects open conversations", async () => {
    const { service } = setup({ conv: conversation({ status: "active" }) });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryConversationNotClosedError,
    );
  });

  it("rejects missing feedback", async () => {
    const { service } = setup({ feedback: null });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryFeedbackMissingError,
    );
  });

  it("rejects answers without a feedback item", async () => {
    const { service } = setup({ feedback: feedbackArtifact([]) });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryItemInvalidError,
    );
  });

  it("rejects items without a question", async () => {
    const { service } = setup({
      feedback: feedbackArtifact([practiceItem({ questionMessageId: null })]),
    });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryNotRetryableError,
    );
  });

  it("rejects question mismatch", async () => {
    const { service } = setup();
    await expect(
      service.submitRetry({ ...baseInput, questionMessageId: "a9" }),
    ).rejects.toThrow(RetryItemInvalidError);
  });

  it("rejects invalid message roles", async () => {
    const { service } = setup({
      messages: [
        msg("a1", "user", "Not a question.", 0),
        msg("u1", "user", "Compression was hard.", 1),
      ],
    });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryReferenceInvalidError,
    );
  });

  it("rejects wrong chronological order", async () => {
    const { service } = setup({
      messages: [
        msg("u1", "user", "Compression was hard.", 0),
        msg("a1", "assistant", "Tell me about a challenge?", 1),
      ],
    });
    await expect(service.submitRetry(baseInput)).rejects.toThrow(
      RetryReferenceInvalidError,
    );
  });

  it("replays duplicate client keys without a new LLM call", async () => {
    const existing = retryRow({});
    const { service, provider, retryRepository } = setup({ byKey: existing });
    const result = await service.submitRetry(baseInput);
    expect(result.created).toBe(false);
    expect(result.retry.id).toBe("r1");
    expect(provider.generateRetryFeedback).not.toHaveBeenCalled();
    expect(retryRepository.createSupersedingRetry).not.toHaveBeenCalled();
  });

  it("keeps provider failure as 201 failed state with durable answer", async () => {
    const { service, retryRepository } = setup({
      providerError: new Error("llm down"),
    });
    const result = await service.submitRetry(baseInput);
    expect(result.created).toBe(true);
    expect(result.retry.feedbackStatus).toBe("failed");
    expect(result.retry.feedback).toBeNull();
    expect(result.retry.retryAnswer).toBe(baseInput.retryAnswer);
    expect(retryRepository.createSupersedingRetry).toHaveBeenCalledOnce();
  });

  it("supersedes the prior current retry for the same answer", async () => {
    const { service, retryRepository } = setup({
      currentRetry: retryRow({ id: "r0", retryClientKey: "key-0" }),
    });
    const result = await service.submitRetry(baseInput);
    expect(result.created).toBe(true);
    expect(retryRepository.createSupersedingRetry).toHaveBeenCalledOnce();
  });

  it("leaves the original M11 feedback untouched", async () => {
    const artifact = feedbackArtifact([practiceItem()]);
    const before = JSON.stringify(artifact);
    const { service } = setup({ feedback: artifact });
    await service.submitRetry(baseInput);
    expect(JSON.stringify(artifact)).toBe(before);
  });

  it("reads back a persisted retry with original text", async () => {
    const { service } = setup({ currentRetry: retryRow({}) });
    const view = await service.getCurrentRetry("conv-1", "u1");
    expect(view.retry.id).toBe("r1");
    expect(view.originalQuestion).toContain("challenge");
    expect(view.originalAnswer).toContain("Compression was hard");
  });

  it("getCurrentRetry 404s when absent", async () => {
    const { service } = setup({ currentRetry: null });
    await expect(service.getCurrentRetry("conv-1", "u9")).rejects.toThrow(
      RetryNotFoundError,
    );
  });
});
