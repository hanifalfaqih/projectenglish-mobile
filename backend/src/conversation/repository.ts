import { PrismaClient, Prisma } from "@prisma/client";
import type { ConversationState } from "./state/schema.js";
import { INITIAL_STATE } from "./state/schema.js";

export interface ConversationRecord {
  id: string;
  status: "active" | "closed";
  state: ConversationState;
  stateVersion: number;
  experienceProfileId: string | null;
  createdAt: Date;
  updatedAt: Date;
}

export interface MessageRecord {
  id: string;
  conversationId: string;
  role: "system" | "user" | "assistant";
  content: string;
  promptVersion: string | null;
  clientTurnId: string | null;
  createdAt: Date;
}

export interface PersistTurnInput {
  conversationId: string;
  assistantMessage: string;
  promptVersion: string;
  state: ConversationState;
  stateVersion: number;
  close: boolean;
}

export class ConversationRepository {
  constructor(private readonly prisma: PrismaClient) {}

  async create(
    experienceProfileId?: string | null,
  ): Promise<ConversationRecord> {
    const row = await this.prisma.conversation.create({
      data: {
        state: INITIAL_STATE as unknown as Prisma.InputJsonValue,
        experienceProfileId: experienceProfileId ?? null,
      },
    });
    return {
      id: row.id,
      status: row.status,
      state: row.state as ConversationState,
      stateVersion: row.stateVersion,
      experienceProfileId: row.experienceProfileId,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }

  async findById(id: string): Promise<ConversationRecord | null> {
    const row = await this.prisma.conversation.findUnique({ where: { id } });
    if (!row) return null;
    return {
      id: row.id,
      status: row.status,
      state: row.state as ConversationState,
      stateVersion: row.stateVersion,
      experienceProfileId: row.experienceProfileId,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }

  async findUserMessageByClientTurnId(
    conversationId: string,
    clientTurnId: string,
  ): Promise<MessageRecord | null> {
    const row = await this.prisma.message.findUnique({
      where: {
        conversationId_clientTurnId: { conversationId, clientTurnId },
      },
    });
    if (!row) return null;
    return {
      id: row.id,
      conversationId: row.conversationId,
      role: row.role,
      content: row.content,
      promptVersion: row.promptVersion,
      clientTurnId: row.clientTurnId,
      createdAt: row.createdAt,
    };
  }

  async findFirstAssistantAfter(
    conversationId: string,
    afterCreatedAt: Date,
  ): Promise<MessageRecord | null> {
    const row = await this.prisma.message.findFirst({
      where: {
        conversationId,
        role: "assistant",
        createdAt: { gt: afterCreatedAt },
      },
      orderBy: [{ createdAt: "asc" }, { id: "asc" }],
    });
    if (!row) return null;
    return {
      id: row.id,
      conversationId: row.conversationId,
      role: row.role,
      content: row.content,
      promptVersion: row.promptVersion,
      clientTurnId: row.clientTurnId,
      createdAt: row.createdAt,
    };
  }

  async appendUserMessage(
    conversationId: string,
    content: string,
    clientTurnId: string,
  ): Promise<MessageRecord> {
    const row = await this.prisma.message.create({
      data: {
        conversationId,
        role: "user",
        content,
        clientTurnId,
      },
    });
    return {
      id: row.id,
      conversationId: row.conversationId,
      role: row.role,
      content: row.content,
      promptVersion: row.promptVersion,
      clientTurnId: row.clientTurnId,
      createdAt: row.createdAt,
    };
  }

  async loadRecentMessages(
    conversationId: string,
    limit: number,
  ): Promise<MessageRecord[]> {
    const rows = await this.prisma.message.findMany({
      where: { conversationId },
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      take: limit,
    });
    rows.reverse();
    return rows.map((row) => ({
      id: row.id,
      conversationId: row.conversationId,
      role: row.role,
      content: row.content,
      promptVersion: row.promptVersion,
      clientTurnId: row.clientTurnId,
      createdAt: row.createdAt,
    }));
  }

  /**
   * Returns the COMPLETE transcript for a conversation in deterministic
   * chronological order (createdAt asc, id asc). Used by M11 feedback
   * generation, which must consider the whole interview — never the recent
   * window. Read-only; does not modify the Message schema.
   */
  async loadAllMessages(conversationId: string): Promise<MessageRecord[]> {
    const rows = await this.prisma.message.findMany({
      where: { conversationId },
      orderBy: [{ createdAt: "asc" }, { id: "asc" }],
    });
    return rows.map((row) => ({
      id: row.id,
      conversationId: row.conversationId,
      role: row.role,
      content: row.content,
      promptVersion: row.promptVersion,
      clientTurnId: row.clientTurnId,
      createdAt: row.createdAt,
    }));
  }

  async persistTurn(input: PersistTurnInput): Promise<MessageRecord> {
    const result = await this.prisma.$transaction(async (tx) => {
      const message = await tx.message.create({
        data: {
          conversationId: input.conversationId,
          role: "assistant",
          content: input.assistantMessage,
          promptVersion: input.promptVersion,
        },
      });

      await tx.conversation.update({
        where: { id: input.conversationId },
        data: {
          state: input.state as Prisma.InputJsonValue,
          stateVersion: input.stateVersion,
          ...(input.close ? { status: "closed" } : {}),
        },
      });

      return message;
    });

    return {
      id: result.id,
      conversationId: result.conversationId,
      role: result.role,
      content: result.content,
      promptVersion: result.promptVersion,
      clientTurnId: result.clientTurnId,
      createdAt: result.createdAt,
    };
  }
}
