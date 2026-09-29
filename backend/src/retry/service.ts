import type {
  ConversationRepository,
  MessageRecord,
} from "../conversation/repository.js";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import type { ExperienceProfileReader } from "../conversation/service.js";
import type { FeedbackRepository } from "../feedback/repository.js";
import type { AnswerFeedbackItem, FeedbackArtifact } from "../feedback/schema.js";
import { RETRY_FEEDBACK_PROMPT_VERSION } from "./prompt.js";
import type { RetryFeedbackProvider } from "./provider.js";
import type {
  CreateSupersedingRetryInput,
  RetryRepository,
} from "./repository.js";
import {
  parseRetryFeedbackOutput,
  type RetryArtifact,
  type RetryFeedback,
  type RetryFeedbackProviderInput,
} from "./schema.js";

// --- Error taxonomy (mapped to HTTP by routes) ---

export class RetryConversationNotFoundError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Conversation not found: ${conversationId}`);
    this.name = "RetryConversationNotFoundError";
  }
}

export class RetryConversationNotClosedError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Conversation is not closed: ${conversationId}`);
    this.name = "RetryConversationNotClosedError";
  }
}

export class RetryFeedbackMissingError extends Error {
  constructor(public readonly conversationId: string) {
    super(`No M11 feedback exists for conversation: ${conversationId}`);
    this.name = "RetryFeedbackMissingError";
  }
}

/** Referenced item not found in Feedback, or answer↔question mismatch. */
export class RetryItemInvalidError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RetryItemInvalidError";
  }
}

/** Message reference invalid (nonexistent / wrong role / wrong conversation). */
export class RetryReferenceInvalidError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RetryReferenceInvalidError";
  }
}

/** Item is non-retryable (e.g. null questionMessageId on the feedback item). */
export class RetryNotRetryableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RetryNotRetryableError";
  }
}

export class RetryInProgressError extends Error {
  constructor(
    public readonly conversationId: string,
    public readonly answerMessageId: string,
  ) {
    super(
      `Retry generation already in progress: ${conversationId}:${answerMessageId}`,
    );
    this.name = "RetryInProgressError";
  }
}

export class RetryNotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RetryNotFoundError";
  }
}

export class RetryAlreadyGeneratedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RetryAlreadyGeneratedError";
  }
}

function isUniqueConstraintError(err: unknown): boolean {
  return (
    typeof err === "object" &&
    err !== null &&
    "code" in err &&
    (err as { code: string }).code === "P2002"
  );
}

export interface SubmitRetryInput {
  conversationId: string;
  answerMessageId: string;
  questionMessageId: string;
  retryAnswer: string;
  retryClientKey: string;
}

/**
 * A retry artifact enriched with the resolved original question/answer text
 * (from the persisted Messages), so the frontend can show the original
 * question without a separate message-read path or M11 changes. Text is best
 * effort: empty string when the referenced message can no longer be resolved.
 */
export interface RetryView {
  retry: RetryArtifact;
  originalQuestion: string;
  originalAnswer: string;
}

export interface RetrySubmitResult extends RetryView {
  /** true = a new row was created (201); false = idempotent existing (200). */
  created: boolean;
}

export interface RetryServiceDependencies {
  conversationRepository: ConversationRepository;
  feedbackRepository: FeedbackRepository;
  retryRepository: RetryRepository;
  provider: RetryFeedbackProvider;
  experienceProfiles?: ExperienceProfileReader;
  promptVersion?: string;
}

/**
 * Orchestrates M12 deliberate retry. Fully isolated from the
 * interview turn pipeline: never calls ConversationService.processTurn, never
 * writes the original Conversation / Messages / ConversationState, and never
 * mutates the immutable M11 Feedback. Retry answers are durably committed
 * BEFORE the external LLM call, so a feedback failure never loses the answer.
 */
export class RetryService {
  private readonly activeRetries = new Set<string>();

  constructor(private readonly deps: RetryServiceDependencies) {}

  private key(conversationId: string, answerMessageId: string): string {
    return `${conversationId}:${answerMessageId}`;
  }

  /** Read-only fetch of the current retry for an answer. Never generates. */
  async getCurrentRetry(
    conversationId: string,
    answerMessageId: string,
  ): Promise<RetryView> {
    const conversation =
      await this.deps.conversationRepository.findById(conversationId);
    if (!conversation) {
      throw new RetryConversationNotFoundError(conversationId);
    }
    const current = await this.deps.retryRepository.findCurrentByAnswer(
      conversationId,
      answerMessageId,
    );
    if (!current) {
      throw new RetryNotFoundError(
        `No retry for ${conversationId}:${answerMessageId}`,
      );
    }
    const { originalQuestion, originalAnswer } = await this.resolveText(
      conversationId,
      current.answerMessageId,
      current.questionMessageId,
    );
    return { retry: current, originalQuestion, originalAnswer };
  }

  /** Best-effort resolution of original question/answer text for the UI. */
  private async resolveText(
    conversationId: string,
    answerMessageId: string,
    questionMessageId: string | null,
  ): Promise<{ originalQuestion: string; originalAnswer: string }> {
    const messages =
      await this.deps.conversationRepository.loadAllMessages(conversationId);
    const byId = new Map(messages.map((m) => [m.id, m]));
    return {
      originalQuestion: questionMessageId
        ? (byId.get(questionMessageId)?.content ?? "")
        : "",
      originalAnswer: byId.get(answerMessageId)?.content ?? "",
    };
  }

  /**
   * Submit a retry answer: validate eligibility, dedup by retryClientKey,
   * atomically create+supersede the answer, then generate feedback outside the
   * transaction. Provider failure still returns 201 with feedbackStatus:failed.
   */
  async submitRetry(input: SubmitRetryInput): Promise<RetrySubmitResult> {
    // Idempotency FIRST: an exact duplicate retryClientKey returns the existing
    // row (200) with no new row, no supersession, and no LLM call — even if the
    // existing row has since been superseded by a newer retry.
    const existing = await this.deps.retryRepository.findByRetryClientKey(
      input.retryClientKey,
    );
    if (existing) {
      const text = await this.resolveText(
        existing.conversationId,
        existing.answerMessageId,
        existing.questionMessageId,
      );
      return { retry: existing, created: false, ...text };
    }

    const { conversation, item, answer, question } = await this.validate(input);

    // Concurrency guard: reject overlapping same-answer generation (409).
    const guardKey = this.key(input.conversationId, input.answerMessageId);
    if (this.activeRetries.has(guardKey)) {
      throw new RetryInProgressError(
        input.conversationId,
        input.answerMessageId,
      );
    }
    this.activeRetries.add(guardKey);
    try {
      return await this.doSubmit(input, conversation, item, answer, question);
    } finally {
      this.activeRetries.delete(guardKey);
    }
  }

  /**
   * Regenerate feedback for the current retry (failure recovery). Never creates
   * a new row, never touches M11 Feedback. Provider failure propagates (mapped
   * to 502/504); the answer remains durable and status stays "failed".
   */
  async regenerateFeedback(
    conversationId: string,
    answerMessageId: string,
  ): Promise<RetryView> {
    const conversation =
      await this.deps.conversationRepository.findById(conversationId);
    if (!conversation) {
      throw new RetryConversationNotFoundError(conversationId);
    }
    const current = await this.deps.retryRepository.findCurrentByAnswer(
      conversationId,
      answerMessageId,
    );
    if (!current) {
      throw new RetryNotFoundError(
        `No retry for ${conversationId}:${answerMessageId}`,
      );
    }
    if (current.feedbackStatus === "generated") {
      throw new RetryAlreadyGeneratedError(
        `Retry feedback already generated: ${current.id}`,
      );
    }

    const guardKey = this.key(conversationId, answerMessageId);
    if (this.activeRetries.has(guardKey)) {
      throw new RetryInProgressError(conversationId, answerMessageId);
    }
    this.activeRetries.add(guardKey);
    try {
      // Re-resolve the original question/answer + feedback item as context.
      const feedback =
        await this.deps.feedbackRepository.findByConversationId(conversationId);
      if (!feedback) {
        throw new RetryFeedbackMissingError(conversationId);
      }
      const messages =
        await this.deps.conversationRepository.loadAllMessages(conversationId);
      const byId = new Map(messages.map((m) => [m.id, m]));
      const answer = byId.get(current.answerMessageId);
      const question = current.questionMessageId
        ? byId.get(current.questionMessageId)
        : undefined;
      const item = feedback.answerItems.find(
        (i) => i.answerMessageId === current.answerMessageId,
      );
      const persisted = await this.generateAndPersist(
        current.id,
        conversation.experienceProfileId,
        question?.content ?? "",
        answer?.content ?? "",
        current.retryAnswer,
        item,
        /* propagateFailure */ true,
      );
      return {
        retry: persisted,
        originalQuestion: question?.content ?? "",
        originalAnswer: answer?.content ?? "",
      };
    } finally {
      this.activeRetries.delete(guardKey);
    }
  }

  // --- internals ---

  private async validate(input: SubmitRetryInput): Promise<{
    conversation: { experienceProfileId: string | null };
    feedback: FeedbackArtifact;
    item: AnswerFeedbackItem;
    answer: MessageRecord;
    question: MessageRecord;
  }> {
    const conversation =
      await this.deps.conversationRepository.findById(input.conversationId);
    if (!conversation) {
      throw new RetryConversationNotFoundError(input.conversationId);
    }
    if (conversation.status !== "closed") {
      throw new RetryConversationNotClosedError(input.conversationId);
    }

    // Require M11 Feedback to exist (backend-authoritative).
    const feedback = await this.deps.feedbackRepository.findByConversationId(
      input.conversationId,
    );
    if (!feedback) {
      throw new RetryFeedbackMissingError(input.conversationId);
    }

    // Locate the feedback item for this answer.
    const item = feedback.answerItems.find(
      (i) => i.answerMessageId === input.answerMessageId,
    );
    if (!item) {
      throw new RetryItemInvalidError(
        `No feedback item references answer ${input.answerMessageId}`,
      );
    }

    // M11 v0.3 (W-6/W-7): only a genuine practice opportunity is a valid
    // Practice Again target. Praise-only observations
    // (practiceOpportunity === false) and legacy items without the marker
    // (normalized to false on read) are not retryable — even when their
    // message references are otherwise valid.
    if (item.practiceOpportunity !== true) {
      throw new RetryNotRetryableError(
        `Feedback item for ${input.answerMessageId} is not a practice opportunity (no meaningful weakness to retry)`,
      );
    }

    // The feedback item must itself be retryable (non-null question).
    if (!item.questionMessageId) {
      throw new RetryNotRetryableError(
        `Feedback item for ${input.answerMessageId} is not retryable (no question)`,
      );
    }

    // The requested questionMessageId must match the item's question.
    if (item.questionMessageId !== input.questionMessageId) {
      throw new RetryItemInvalidError(
        `questionMessageId does not match the feedback item for this answer`,
      );
    }

    // Validate the actual Messages (exist, belong to conversation, roles,
    // chronological relationship). loadAllMessages is scoped to the conversation.
    const messages = await this.deps.conversationRepository.loadAllMessages(
      input.conversationId,
    );
    const byId = new Map(messages.map((m) => [m.id, m]));

    const answer = byId.get(input.answerMessageId);
    if (!answer || answer.role !== "user") {
      throw new RetryReferenceInvalidError(
        `Invalid answer message reference: ${input.answerMessageId}`,
      );
    }

    const question = byId.get(input.questionMessageId);
    if (!question || question.role !== "assistant") {
      throw new RetryReferenceInvalidError(
        `Invalid question message reference: ${input.questionMessageId}`,
      );
    }

    // Question must precede the answer chronologically.
    const precedes =
      question.createdAt.getTime() < answer.createdAt.getTime() ||
      (question.createdAt.getTime() === answer.createdAt.getTime() &&
        question.id < answer.id);
    if (!precedes) {
      throw new RetryReferenceInvalidError(
        `Question ${input.questionMessageId} does not precede answer ${input.answerMessageId}`,
      );
    }

    return {
      conversation: { experienceProfileId: conversation.experienceProfileId },
      feedback,
      item,
      answer,
      question,
    };
  }

  private async doSubmit(
    input: SubmitRetryInput,
    conversation: { experienceProfileId: string | null },
    item: AnswerFeedbackItem,
    answer: MessageRecord,
    question: MessageRecord,
  ): Promise<RetrySubmitResult> {
    // Step 3: atomic create+supersede — the answer is durably committed
    // BEFORE any LLM call. A concurrent exact-duplicate retryClientKey surfaces
    // as P2002 here; recover by returning the persisted row (idempotent 200).
    const createInput: CreateSupersedingRetryInput = {
      conversationId: input.conversationId,
      feedbackId: await this.resolveFeedbackId(input.conversationId),
      answerMessageId: input.answerMessageId,
      questionMessageId: input.questionMessageId,
      retryClientKey: input.retryClientKey,
      retryAnswer: input.retryAnswer,
    };

    let created: RetryArtifact;
    try {
      created = await this.deps.retryRepository.createSupersedingRetry(
        createInput,
      );
    } catch (err) {
      if (isUniqueConstraintError(err)) {
        const persisted =
          await this.deps.retryRepository.findByRetryClientKey(
            input.retryClientKey,
          );
        if (persisted) {
          return {
            retry: persisted,
            created: false,
            originalQuestion: question.content,
            originalAnswer: answer.content,
          };
        }
      }
      throw err;
    }

    // Step 4/5: generate feedback OUTSIDE any DB transaction. On failure
    // the answer stays durable and status becomes "failed" (still 201).
    const persisted = await this.generateAndPersist(
      created.id,
      conversation.experienceProfileId,
      question.content,
      answer.content,
      input.retryAnswer,
      item,
      /* propagateFailure */ false,
    );
    return {
      retry: persisted,
      created: true,
      originalQuestion: question.content,
      originalAnswer: answer.content,
    };
  }

  /**
   * Resolve the persisted Feedback row id for the RetryPractice.feedbackId FK.
   * The M11 FeedbackArtifact does not expose its id, so the FeedbackRepository
   * provides a small conversation-scoped id lookup.
   */
  private async resolveFeedbackId(conversationId: string): Promise<string> {
    const id =
      await this.deps.feedbackRepository.findIdByConversationId(conversationId);
    if (!id) {
      throw new RetryFeedbackMissingError(conversationId);
    }
    return id;
  }

  private async generateAndPersist(
    retryId: string,
    experienceProfileId: string | null,
    originalQuestion: string,
    originalAnswer: string,
    retryAnswer: string,
    item: AnswerFeedbackItem | undefined,
    propagateFailure: boolean,
  ): Promise<RetryArtifact> {
    const providerInput: RetryFeedbackProviderInput = {
      conversationId: "",
      originalQuestion,
      originalAnswer,
      retryAnswer,
      originalFeedbackItem: {
        whatWorked: item?.whatWorked,
        couldImprove: item?.couldImprove,
        tryNextTime: item?.tryNextTime,
      },
    };

    if (experienceProfileId && this.deps.experienceProfiles) {
      const profile =
        await this.deps.experienceProfiles.findById(experienceProfileId);
      if (profile) {
        providerInput.experience = profile.items.map((i) => ({
          title: i.title,
          organization: i.organization,
          role: i.role,
          description: i.description,
          skills: i.skills,
        }));
      }
    }

    let feedback: RetryFeedback | null = null;
    try {
      const raw = await this.deps.provider.generateRetryFeedback(providerInput);
      const parsed = parseRetryFeedbackOutput(raw);
      if (!parsed.ok || !parsed.feedback) {
        throw new ProviderError(
          `Malformed retry feedback output: ${parsed.error ?? "unknown"}`,
        );
      }
      feedback = parsed.feedback;
    } catch (err) {
      // On the submit path, persist "failed" and swallow (still 201). On the
      // regenerate path, persist "failed" and rethrow (mapped to 502/504).
      await this.deps.retryRepository.setFeedback(
        retryId,
        null,
        null,
        "failed",
      );
      if (propagateFailure) {
        if (err instanceof ProviderTimeoutError) throw err;
        if (err instanceof ProviderError) throw err;
        throw new ProviderError("Retry feedback provider failed", err);
      }
      const failed = await this.deps.retryRepository.findById(retryId);
      if (!failed) {
        throw new RetryNotFoundError(`Retry disappeared: ${retryId}`);
      }
      return failed;
    }

    const promptVersion =
      this.deps.promptVersion ?? RETRY_FEEDBACK_PROMPT_VERSION;
    return this.deps.retryRepository.setFeedback(
      retryId,
      feedback,
      promptVersion,
      "generated",
    );
  }
}
