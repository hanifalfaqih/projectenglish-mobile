import { z } from "zod";

/**
 * Actual M11 — Actionable Interview Feedback domain (Contract v0.2).
 *
 * Qualitative only. Evaluation lenses are Content / Clarity / English;
 * Professional Communication is a cross-cutting observation, NOT a fourth
 * dimension. There are NO numeric scores/percentages/rankings/grades.
 *
 * Answer-level items are grounded in the candidate's actual answers via stable
 * persisted Message.id references (answerMessageId / questionMessageId). The
 * LLM output is untrusted; the backend validates references before persistence
 * (see service).
 */

// Engineering bounds (implementation-level; not new product requirements).
export const FEEDBACK_LIMITS = {
  OVERALL_MAX: 4000,
  ANSWER_ITEM_FIELD_MAX: 1500,
  MAX_ANSWER_ITEMS: 50,
  MAX_PROF_COMM: 20,
  PROF_COMM_MAX: 1000,
} as const;

const nonEmpty = (max: number) =>
  z.string().trim().min(1).max(max);

/**
 * A single answer-level feedback item as PROPOSED by the LLM (untrusted).
 * `.strict()` rejects unknown fields — in particular any numeric field. The
 * three qualitative fields are each optional (not forced per answer).
 *
 * `practiceOpportunity` is REQUIRED on newly generated output (M11 v0.3 W-6):
 * the model must explicitly declare whether the item identifies a meaningful,
 * actionable weakness worth deliberate retry (true) or is a praise-only
 * observation (false). The backend never infers it from field presence.
 */
export const ProposedAnswerFeedbackItem = z
  .object({
    answerMessageId: z.string().min(1),
    questionMessageId: z.string().min(1).nullable().optional(),
    practiceOpportunity: z.boolean(),
    whatWorked: nonEmpty(FEEDBACK_LIMITS.ANSWER_ITEM_FIELD_MAX).optional(),
    couldImprove: nonEmpty(FEEDBACK_LIMITS.ANSWER_ITEM_FIELD_MAX).optional(),
    tryNextTime: nonEmpty(FEEDBACK_LIMITS.ANSWER_ITEM_FIELD_MAX).optional(),
  })
  .strict();
export type ProposedAnswerFeedbackItem = z.infer<
  typeof ProposedAnswerFeedbackItem
>;

/**
 * Raw structured output returned by the FeedbackProvider (untrusted).
 * Strict schema: any unknown/numeric field fails validation.
 */
export const FeedbackOutput = z
  .object({
    overall: nonEmpty(FEEDBACK_LIMITS.OVERALL_MAX),
    answerItems: z
      .array(ProposedAnswerFeedbackItem)
      .max(FEEDBACK_LIMITS.MAX_ANSWER_ITEMS)
      .default([]),
    professionalCommunication: z
      .array(nonEmpty(FEEDBACK_LIMITS.PROF_COMM_MAX))
      .max(FEEDBACK_LIMITS.MAX_PROF_COMM)
      .optional(),
  })
  .strict();
export type FeedbackOutput = z.infer<typeof FeedbackOutput>;

/**
 * The persisted answer-level item. Same shape as proposed but with
 * questionMessageId normalized to string | null (backend-validated).
 */
export interface AnswerFeedbackItem {
  answerMessageId: string;
  questionMessageId: string | null;
  /**
   * M11 v0.3 (W-6): true when the item identifies a meaningful weakness the
   * candidate can concretely address in a retry (a Practice Again target);
   * false for praise-only observations. Explicitly model-declared on new
   * items; legacy persisted items without the field read back as false
   * (see repository normalization) so they never silently become practice
   * targets.
   */
  practiceOpportunity: boolean;
  /**
   * M13 (additive, derived): the text of the referenced questionMessageId,
   * resolved at read time from the transcript. Not persisted in the Feedback
   * artifact (which stores only ids); populated by the service so the frontend
   * can present/speak the original question (e.g. before an M12 retry). Null
   * when there is no question reference or it can no longer be resolved.
   */
  questionText?: string | null;
  whatWorked?: string;
  couldImprove?: string;
  tryNextTime?: string;
}

/** The persisted / API feedback artifact. */
export interface FeedbackArtifact {
  conversationId: string;
  promptVersion: string;
  overall: string;
  answerItems: AnswerFeedbackItem[];
  professionalCommunication: string[] | null;
  createdAt: Date;
}

/** Input assembled by the service and passed to the FeedbackProvider. */
export interface FeedbackTranscriptMessage {
  id: string;
  role: "system" | "user" | "assistant";
  content: string;
}

export interface FeedbackExperienceItem {
  title: string;
  organization: string | null;
  role: string | null;
  description: string;
  skills: string[];
}

export interface FeedbackInput {
  conversationId: string;
  transcript: FeedbackTranscriptMessage[];
  state: {
    phase: string;
    topics: { id: string; label: string; covered: boolean }[];
    questionCount: number;
  };
  experience?: FeedbackExperienceItem[];
}

/**
 * Parse untrusted provider output into a validated FeedbackOutput. Structural
 * validation only (shape, bounds, no unknown/numeric fields). Reference
 * validation against the real transcript happens in the service.
 */
export function parseFeedbackOutput(raw: unknown): {
  ok: boolean;
  output: FeedbackOutput | null;
  error?: string;
} {
  const parsed = FeedbackOutput.safeParse(raw);
  if (!parsed.success) {
    return { ok: false, output: null, error: parsed.error.message };
  }
  return { ok: true, output: parsed.data };
}
