import { z } from "zod";

/**
 * M12 — Deliberate Retry / Deliberate Practice domain.
 *
 * A retry re-answers ONE specific original interview answer (identified by an
 * M11 feedback item). Retry feedback reuses M11's qualitative lens at
 * single-answer granularity: Content / Clarity / English, with Professional
 * Communication as a cross-cutting observation only. There are NO numeric
 * scores/percentages/rankings/grades.
 *
 * Retry submissions are append-only: each submission creates a new
 * RetryPractice row that is never replaced or deleted. Lifecycle metadata
 * (supersededAt) and feedback fields may be updated. The LLM output is
 * untrusted; the backend validates it (shape) before persistence.
 */

// Engineering bounds (implementation-level; not new product requirements).
export const RETRY_LIMITS = {
  RETRY_ANSWER_MAX: 8000,
  OVERALL_MAX: 4000,
  FIELD_MAX: 1500,
  MAX_PROF_COMM: 20,
  PROF_COMM_MAX: 1000,
} as const;

const nonEmpty = (max: number) => z.string().trim().min(1).max(max);

export const RetryFeedbackStatusValues = [
  "pending",
  "generated",
  "failed",
] as const;
export type RetryFeedbackStatus = (typeof RetryFeedbackStatusValues)[number];

/**
 * POST /conversations/:id/retries request body (from the client, untrusted).
 * `.strict()` rejects unknown fields. `retryClientKey` is client-generated and
 * globally unique; it is NEVER conflated with the interview `clientTurnId`.
 */
export const SubmitRetryRequest = z
  .object({
    answerMessageId: z.string().min(1),
    questionMessageId: z.string().min(1),
    retryAnswer: nonEmpty(RETRY_LIMITS.RETRY_ANSWER_MAX),
    retryClientKey: z.string().min(1).max(200),
  })
  .strict();
export type SubmitRetryRequest = z.infer<typeof SubmitRetryRequest>;

/**
 * Raw structured output returned by the RetryFeedbackProvider (untrusted).
 * Strict schema: any unknown/numeric field fails validation. Single-answer
 * granularity — no per-answer array, since a retry targets exactly one answer.
 */
export const RetryFeedbackOutput = z
  .object({
    overall: nonEmpty(RETRY_LIMITS.OVERALL_MAX),
    whatWorked: nonEmpty(RETRY_LIMITS.FIELD_MAX).optional(),
    couldImprove: nonEmpty(RETRY_LIMITS.FIELD_MAX).optional(),
    tryNextTime: nonEmpty(RETRY_LIMITS.FIELD_MAX).optional(),
    professionalCommunication: z
      .array(nonEmpty(RETRY_LIMITS.PROF_COMM_MAX))
      .max(RETRY_LIMITS.MAX_PROF_COMM)
      .nullable()
      .optional(),
  })
  .strict();
export type RetryFeedbackOutput = z.infer<typeof RetryFeedbackOutput>;

/** The qualitative retry feedback payload, as persisted (Json) and returned. */
export interface RetryFeedback {
  overall: string;
  whatWorked?: string;
  couldImprove?: string;
  tryNextTime?: string;
  professionalCommunication: string[] | null;
}

/** The persisted / API retry artifact (current retry for an answer). */
export interface RetryArtifact {
  id: string;
  conversationId: string;
  feedbackId: string;
  answerMessageId: string;
  questionMessageId: string | null;
  retryClientKey: string;
  retryAnswer: string;
  feedback: RetryFeedback | null;
  feedbackPromptVersion: string | null;
  feedbackStatus: RetryFeedbackStatus;
  supersededAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
}

/** Input assembled by the service and passed to the RetryFeedbackProvider. */
export interface RetryExperienceItem {
  title: string;
  organization: string | null;
  role: string | null;
  description: string;
  skills: string[];
}

export interface RetryFeedbackProviderInput {
  conversationId: string;
  /** The original interviewer question (untrusted background data). */
  originalQuestion: string;
  /** The candidate's original answer (untrusted background data). */
  originalAnswer: string;
  /** The candidate's new retry answer — this is what the model evaluates. */
  retryAnswer: string;
  /**
   * The matched M11 feedback item for the original answer, as guidance context
   * (untrusted background data). Any of the qualitative fields may be absent.
   */
  originalFeedbackItem: {
    whatWorked?: string;
    couldImprove?: string;
    tryNextTime?: string;
  };
  /** Experience Profile context when present (untrusted background data). */
  experience?: RetryExperienceItem[];
}

/**
 * Parse untrusted provider output into validated RetryFeedback. Structural
 * validation only (shape, bounds, no unknown/numeric fields).
 */
export function parseRetryFeedbackOutput(raw: unknown): {
  ok: boolean;
  feedback: RetryFeedback | null;
  error?: string;
} {
  const parsed = RetryFeedbackOutput.safeParse(raw);
  if (!parsed.success) {
    return { ok: false, feedback: null, error: parsed.error.message };
  }
  const out = parsed.data;
  const feedback: RetryFeedback = {
    overall: out.overall,
    professionalCommunication: out.professionalCommunication ?? null,
  };
  if (out.whatWorked) feedback.whatWorked = out.whatWorked;
  if (out.couldImprove) feedback.couldImprove = out.couldImprove;
  if (out.tryNextTime) feedback.tryNextTime = out.tryNextTime;
  return { ok: true, feedback };
}
