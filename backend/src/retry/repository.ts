import { PrismaClient, Prisma } from "@prisma/client";
import type {
  RetryArtifact,
  RetryFeedback,
  RetryFeedbackStatus,
} from "./schema.js";

export interface CreateSupersedingRetryInput {
  conversationId: string;
  feedbackId: string;
  answerMessageId: string;
  questionMessageId: string | null;
  retryClientKey: string;
  retryAnswer: string;
}

type RetryRow = {
  id: string;
  conversationId: string;
  feedbackId: string;
  answerMessageId: string;
  questionMessageId: string | null;
  retryClientKey: string;
  retryAnswer: string;
  feedback: unknown;
  feedbackPromptVersion: string | null;
  feedbackStatus: RetryFeedbackStatus;
  supersededAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
};

/**
 * Thin data access for the M12 RetryPractice artifact (append-only rows).
 *
 * The "supersede prior + insert new" pair is exposed ONLY via the single atomic
 * `createSupersedingRetry`. There is deliberately no standalone public
 * `insertRetry` or `markSuperseded` method that could be called independently
 * and leave committed state inconsistent (two current rows, or a superseded
 * row without its replacement).
 */
export class RetryRepository {
  constructor(private readonly prisma: PrismaClient) {}

  /** Idempotency lookup by the globally-unique client key. */
  async findByRetryClientKey(key: string): Promise<RetryArtifact | null> {
    const row = await this.prisma.retryPractice.findUnique({
      where: { retryClientKey: key },
    });
    return row ? this.toArtifact(row) : null;
  }

  /** The current (newest, supersededAt IS NULL) retry for an answer. */
  async findCurrentByAnswer(
    conversationId: string,
    answerMessageId: string,
  ): Promise<RetryArtifact | null> {
    const row = await this.prisma.retryPractice.findFirst({
      where: { conversationId, answerMessageId, supersededAt: null },
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
    });
    return row ? this.toArtifact(row) : null;
  }

  async findById(id: string): Promise<RetryArtifact | null> {
    const row = await this.prisma.retryPractice.findUnique({ where: { id } });
    return row ? this.toArtifact(row) : null;
  }

  /**
   * ATOMIC create+supersede in ONE database transaction:
   *   1. mark the previous current retry (if any) for
   *      (conversationId, answerMessageId) as superseded,
   *   2. insert the new RetryPractice row.
   * If either statement fails the whole transaction rolls back: the prior row
   * stays current and the new row is not persisted.
   *
   * The external LLM call is NEVER inside this transaction (see RetryService).
   */
  async createSupersedingRetry(
    input: CreateSupersedingRetryInput,
  ): Promise<RetryArtifact> {
    const row = await this.prisma.$transaction(async (tx) => {
      // Supersede the prior current row(s) for this answer, if any. Scoped so
      // it only ever touches the same-answer current retry.
      await tx.retryPractice.updateMany({
        where: {
          conversationId: input.conversationId,
          answerMessageId: input.answerMessageId,
          supersededAt: null,
        },
        data: { supersededAt: new Date() },
      });

      // Insert the new current row. A duplicate retryClientKey surfaces here as
      // P2002 and rolls back the whole transaction (including the supersede).
      return tx.retryPractice.create({
        data: {
          conversationId: input.conversationId,
          feedbackId: input.feedbackId,
          answerMessageId: input.answerMessageId,
          questionMessageId: input.questionMessageId,
          retryClientKey: input.retryClientKey,
          retryAnswer: input.retryAnswer,
          feedbackStatus: "pending",
          supersededAt: null,
        },
      });
    });
    return this.toArtifact(row);
  }

  /**
   * Persist retry feedback as a separate single-row update. On success:
   * feedback + promptVersion + status="generated". On provider failure:
   * feedback=null + status="failed". Never creates a new row.
   */
  async setFeedback(
    id: string,
    feedback: RetryFeedback | null,
    promptVersion: string | null,
    status: RetryFeedbackStatus,
  ): Promise<RetryArtifact> {
    const row = await this.prisma.retryPractice.update({
      where: { id },
      data: {
        feedback:
          feedback === null
            ? Prisma.JsonNull
            : (feedback as unknown as Prisma.InputJsonValue),
        feedbackPromptVersion: promptVersion,
        feedbackStatus: status,
      },
    });
    return this.toArtifact(row);
  }

  private toArtifact(row: RetryRow): RetryArtifact {
    return {
      id: row.id,
      conversationId: row.conversationId,
      feedbackId: row.feedbackId,
      answerMessageId: row.answerMessageId,
      questionMessageId: row.questionMessageId,
      retryClientKey: row.retryClientKey,
      retryAnswer: row.retryAnswer,
      feedback: (row.feedback as RetryFeedback | null) ?? null,
      feedbackPromptVersion: row.feedbackPromptVersion,
      feedbackStatus: row.feedbackStatus,
      supersededAt: row.supersededAt,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }
}
