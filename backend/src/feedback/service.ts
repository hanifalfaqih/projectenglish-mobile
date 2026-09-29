import type {
  ConversationRepository,
  MessageRecord,
} from "../conversation/repository.js";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import type { ExperienceProfileReader } from "../conversation/service.js";
import { FEEDBACK_PROMPT_VERSION } from "./prompt.js";
import type { FeedbackProvider } from "./provider.js";
import type { FeedbackRepository } from "./repository.js";
import {
  parseFeedbackOutput,
  type AnswerFeedbackItem,
  type FeedbackArtifact,
  type FeedbackInput,
} from "./schema.js";

export class ConversationNotFoundError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Conversation not found: ${conversationId}`);
    this.name = "ConversationNotFoundError";
  }
}

export class ConversationNotClosedError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Conversation is not closed: ${conversationId}`);
    this.name = "ConversationNotClosedError";
  }
}

export class FeedbackInProgressError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Feedback generation already in progress: ${conversationId}`);
    this.name = "FeedbackInProgressError";
  }
}

export class FeedbackNotFoundError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Feedback not found: ${conversationId}`);
    this.name = "FeedbackNotFoundError";
  }
}

export class FeedbackGenerationError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "FeedbackGenerationError";
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

export interface FeedbackGenerateResult {
  feedback: FeedbackArtifact;
  created: boolean; // true = newly generated (201), false = existing (200)
}

export interface FeedbackServiceDependencies {
  conversationRepository: ConversationRepository;
  feedbackRepository: FeedbackRepository;
  provider: FeedbackProvider;
  experienceProfiles?: ExperienceProfileReader;
  promptVersion?: string;
}

/**
 * Orchestrates M11 feedback generation (Contract v0.2). Feedback is generated
 * only for closed conversations, once per conversation, over the complete
 * transcript, with LLM message references validated before persistence.
 * Interview completion is independent of feedback; failure persists nothing.
 */
export class FeedbackService {
  private readonly activeFeedback = new Set<string>();

  constructor(private readonly deps: FeedbackServiceDependencies) {}

  /** Read-only fetch. Never generates. */
  async getFeedback(conversationId: string): Promise<FeedbackArtifact> {
    const conversation =
      await this.deps.conversationRepository.findById(conversationId);
    if (!conversation) {
      throw new ConversationNotFoundError(conversationId);
    }
    const existing =
      await this.deps.feedbackRepository.findByConversationId(conversationId);
    if (!existing) {
      throw new FeedbackNotFoundError(conversationId);
    }
    return this.withQuestionText(conversationId, existing);
  }

  /**
   * M13 (additive, derived read field): attach `questionText` to each answer
   * item by resolving its `questionMessageId` against the persisted transcript.
   * This does NOT mutate the stored artifact (items keep only ids); it enriches
   * the returned read model so the frontend can present/speak the original
   * question (e.g. before an M12 retry). Message loads are read-only.
   */
  private async withQuestionText(
    conversationId: string,
    artifact: FeedbackArtifact,
  ): Promise<FeedbackArtifact> {
    const hasQuestionRef = artifact.answerItems.some(
      (i) => i.questionMessageId,
    );
    if (!hasQuestionRef) return artifact;

    const messages =
      await this.deps.conversationRepository.loadAllMessages(conversationId);
    const contentById = new Map(messages.map((m) => [m.id, m.content]));

    return {
      ...artifact,
      answerItems: artifact.answerItems.map((item) => ({
        ...item,
        questionText: item.questionMessageId
          ? (contentById.get(item.questionMessageId) ?? null)
          : null,
      })),
    };
  }

  /** Explicit generate-or-return. LLM invoked only when no artifact exists. */
  async generateFeedback(
    conversationId: string,
  ): Promise<FeedbackGenerateResult> {
    const conversation =
      await this.deps.conversationRepository.findById(conversationId);
    if (!conversation) {
      throw new ConversationNotFoundError(conversationId);
    }
    if (conversation.status !== "closed") {
      throw new ConversationNotClosedError(conversationId);
    }

    // Return existing without invoking the LLM.
    const existing =
      await this.deps.feedbackRepository.findByConversationId(conversationId);
    if (existing) {
      return { feedback: existing, created: false };
    }

    // In-process concurrency guard (not a distributed lock; single-process MVP).
    if (this.activeFeedback.has(conversationId)) {
      throw new FeedbackInProgressError(conversationId);
    }
    this.activeFeedback.add(conversationId);
    try {
      return await this.doGenerate(conversationId, conversation.state, conversation.experienceProfileId);
    } finally {
      this.activeFeedback.delete(conversationId);
    }
  }

  private async doGenerate(
    conversationId: string,
    state: { phase: string; topics: { id: string; label: string; covered: boolean }[]; questionCount: number },
    experienceProfileId: string | null,
  ): Promise<FeedbackGenerateResult> {
    const messages =
      await this.deps.conversationRepository.loadAllMessages(conversationId);

    const input: FeedbackInput = {
      conversationId,
      transcript: messages.map((m) => ({
        id: m.id,
        role: m.role,
        content: m.content,
      })),
      state: {
        phase: state.phase,
        topics: state.topics,
        questionCount: state.questionCount,
      },
    };

    if (experienceProfileId && this.deps.experienceProfiles) {
      const profile =
        await this.deps.experienceProfiles.findById(experienceProfileId);
      if (profile) {
        input.experience = profile.items.map((i) => ({
          title: i.title,
          organization: i.organization,
          role: i.role,
          description: i.description,
          skills: i.skills,
        }));
      }
    }

    // Call the provider. Provider/timeout errors propagate unchanged so the
    // route maps them to 502/504. Nothing is persisted on failure.
    let raw;
    try {
      raw = await this.deps.provider.generateFeedback(input);
    } catch (err) {
      if (err instanceof ProviderTimeoutError) throw err;
      if (err instanceof ProviderError) throw err;
      throw new ProviderError("Feedback provider failed", err);
    }

    // Structural validation of untrusted output.
    const parsed = parseFeedbackOutput(raw);
    if (!parsed.ok || !parsed.output) {
      throw new FeedbackGenerationError(
        `Malformed feedback output: ${parsed.error ?? "unknown"}`,
      );
    }

    // Reference validation against the real transcript (backend authoritative).
    const validatedItems = this.validateAnswerItems(
      parsed.output.answerItems,
      messages,
    );

    const promptVersion = this.deps.promptVersion ?? FEEDBACK_PROMPT_VERSION;

    // Resolve derived questionText from the already-loaded transcript (no extra
    // DB read); persisted items still store only ids (see withQuestionText).
    const contentById = new Map(messages.map((m) => [m.id, m.content]));
    const attachText = (feedback: FeedbackArtifact): FeedbackArtifact => ({
      ...feedback,
      answerItems: feedback.answerItems.map((item) => ({
        ...item,
        questionText: item.questionMessageId
          ? (contentById.get(item.questionMessageId) ?? null)
          : null,
      })),
    });

    try {
      const feedback = await this.deps.feedbackRepository.create({
        conversationId,
        promptVersion,
        overall: parsed.output.overall,
        answerItems: validatedItems,
        professionalCommunication:
          parsed.output.professionalCommunication ?? null,
      });
      return { feedback: attachText(feedback), created: true };
    } catch (err) {
      // Race: another request persisted first. Return the persisted artifact.
      if (isUniqueConstraintError(err)) {
        const persisted =
          await this.deps.feedbackRepository.findByConversationId(
            conversationId,
          );
        if (persisted) {
          return {
            feedback: await this.withQuestionText(conversationId, persisted),
            created: false,
          };
        }
      }
      throw err;
    }
  }

  /**
   * Validate LLM-proposed message references. Each answer item must reference a
   * real user message in this conversation; questionMessageId (if present) must
   * be a real assistant message in this conversation that precedes the answer.
   * Invalid items are dropped; a valid item with an invalid questionMessageId
   * keeps the item but nulls the reference.
   */
  private validateAnswerItems(
    proposed: {
      answerMessageId: string;
      questionMessageId?: string | null;
      practiceOpportunity: boolean;
      whatWorked?: string;
      couldImprove?: string;
      tryNextTime?: string;
    }[],
    messages: MessageRecord[],
  ): AnswerFeedbackItem[] {
    const byId = new Map(messages.map((m) => [m.id, m]));
    const result: AnswerFeedbackItem[] = [];

    for (const item of proposed) {
      const answer = byId.get(item.answerMessageId);
      // answerMessageId must exist, belong to conversation (guaranteed by
      // loadAllMessages scope), and have role "user".
      if (!answer || answer.role !== "user") continue;

      let questionMessageId: string | null = null;
      if (item.questionMessageId) {
        const question = byId.get(item.questionMessageId);
        if (
          question &&
          question.role === "assistant" &&
          // relationship: the question must precede the answer chronologically
          (question.createdAt.getTime() < answer.createdAt.getTime() ||
            (question.createdAt.getTime() === answer.createdAt.getTime() &&
              question.id < answer.id))
        ) {
          questionMessageId = question.id;
        }
        // else: invalid question ref → drop the reference, keep the item
      }

      const validated: AnswerFeedbackItem = {
        answerMessageId: answer.id,
        questionMessageId,
        // Model-declared practice signal, passed through verbatim (never
        // inferred): true = genuine Practice Again target, false = praise /
        // observation only. Already Zod-validated as boolean upstream.
        practiceOpportunity: item.practiceOpportunity,
      };
      if (item.whatWorked) validated.whatWorked = item.whatWorked;
      if (item.couldImprove) validated.couldImprove = item.couldImprove;
      if (item.tryNextTime) validated.tryNextTime = item.tryNextTime;
      result.push(validated);
    }

    return result;
  }
}
