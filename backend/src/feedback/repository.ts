import { PrismaClient, Prisma } from "@prisma/client";
import type { AnswerFeedbackItem, FeedbackArtifact } from "./schema.js";

export interface PersistFeedbackInput {
  conversationId: string;
  promptVersion: string;
  overall: string;
  answerItems: AnswerFeedbackItem[];
  professionalCommunication: string[] | null;
}

/** Thin data access for the M11 Feedback artifact (1:1 with Conversation). */
export class FeedbackRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async findByConversationId(
    conversationId: string,
  ): Promise<FeedbackArtifact | null> {
    const row = await this.prisma.feedback.findUnique({
      where: { conversationId },
    });
    return row ? this.toArtifact(row) : null;
  }

  /**
   * Additive (M12): resolve the Feedback row id for a conversation without
   * exposing it on the public FeedbackArtifact. Used only as the RetryPractice
   * feedbackId FK anchor. M11 behavior/shape is unchanged.
   */
  async findIdByConversationId(conversationId: string): Promise<string | null> {
    const row = await this.prisma.feedback.findUnique({
      where: { conversationId },
      select: { id: true },
    });
    return row?.id ?? null;
  }

  async create(input: PersistFeedbackInput): Promise<FeedbackArtifact> {
    const row = await this.prisma.feedback.create({
      data: {
        conversationId: input.conversationId,
        promptVersion: input.promptVersion,
        overall: input.overall,
        items: input.answerItems as unknown as Prisma.InputJsonValue,
        professionalCommunication:
          input.professionalCommunication === null
            ? Prisma.JsonNull
            : (input.professionalCommunication as unknown as Prisma.InputJsonValue),
      },
    });
    return this.toArtifact(row);
  }

  private toArtifact(row: {
    conversationId: string;
    promptVersion: string;
    overall: string;
    items: unknown;
    professionalCommunication: unknown;
    createdAt: Date;
  }): FeedbackArtifact {
    return {
      conversationId: row.conversationId,
      promptVersion: row.promptVersion,
      overall: row.overall,
      answerItems: (row.items as AnswerFeedbackItem[]) ?? [],
      professionalCommunication:
        (row.professionalCommunication as string[] | null) ?? null,
      createdAt: row.createdAt,
    };
  }
}
