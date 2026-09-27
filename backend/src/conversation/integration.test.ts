import { describe, it, expect, beforeEach } from "vitest";
import { randomUUID } from "node:crypto";
import { ConversationService, TurnInProgressError } from "./service.js";
import type {
  ConversationRecord,
  MessageRecord,
  PersistTurnInput,
} from "./repository.js";
import type { ConversationRepository } from "./repository.js";
import type { LLMProvider, TurnInput, TurnOutput } from "./provider/index.js";
import type { ConversationState } from "./state/schema.js";
import { INITIAL_STATE, MAX_QUESTIONS } from "./state/schema.js";

/**
 * Deterministic full-stack integration tests for the Conversation Engine.
 *
 * These verify the REAL ConversationService orchestration against an in-memory
 * repository that faithfully mirrors the observable behavior of the real
 * Prisma-backed ConversationRepository:
 *   - append-only messages
 *   - unique (conversationId, clientTurnId) with a P2002-style error
 *   - createdAt ordering for transcript + idempotent-replay lookups
 *   - atomic persistTurn (assistant message + state (+ optional close))
 *
 * No PostgreSQL and no real Qwen provider are involved, so these run in the
 * default deterministic suite. Real-infra behavior is covered separately by the
 * opt-in `*.live.test.ts` files.
 */

class P2002Error extends Error {
  code = "P2002";
  constructor() {
    super("Unique constraint failed on the fields: (`conversationId`,`clientTurnId`)");
    this.name = "P2002Error";
  }
}

interface StoredMessage {
  id: string;
  conversationId: string;
  role: "system" | "user" | "assistant";
  content: string;
  promptVersion: string | null;
  clientTurnId: string | null;
  createdAt: Date;
  seq: number; // monotonic tiebreaker mirroring insertion order
}

interface StoredConversation {
  id: string;
  status: "active" | "closed";
  state: ConversationState;
  stateVersion: number;
  experienceProfileId: string | null;
  createdAt: Date;
  updatedAt: Date;
}

/**
 * The subset of ConversationRepository behavior the ConversationService
 * depends on. We model it structurally (rather than `implements`-ing the
 * concrete Prisma-backed class, which carries a private field) and inject via
 * the same cast the existing unit tests use. This keeps the fake faithful to
 * the observable contract without touching M1-M6 source.
 */
type RepositoryContract = Pick<
  ConversationRepository,
  | "create"
  | "findById"
  | "findUserMessageByClientTurnId"
  | "findFirstAssistantAfter"
  | "appendUserMessage"
  | "loadRecentMessages"
  | "persistTurn"
>;

/**
 * In-memory repository with the same observable contract as the real one.
 * A shared clock guarantees strictly increasing createdAt values so that
 * ordering-based logic (transcript window, findFirstAssistantAfter) is
 * deterministic even within the same millisecond.
 */
class InMemoryRepository implements RepositoryContract {
  private conversations = new Map<string, StoredConversation>();
  private messages: StoredMessage[] = [];
  private clockMs = Date.UTC(2026, 0, 1, 0, 0, 0);
  private seq = 0;

  // Simulate atomic-persist failure for a specific conversation (error tests).
  failPersistForConversationId: string | null = null;

  private nextTimestamp(): Date {
    this.clockMs += 1000;
    return new Date(this.clockMs);
  }

  async create(
    experienceProfileId?: string | null,
  ): Promise<ConversationRecord> {
    const now = this.nextTimestamp();
    const conv: StoredConversation = {
      id: randomUUID(),
      status: "active",
      state: structuredClone(INITIAL_STATE),
      stateVersion: 1,
      experienceProfileId: experienceProfileId ?? null,
      createdAt: now,
      updatedAt: now,
    };
    this.conversations.set(conv.id, conv);
    return this.toRecord(conv);
  }

  async findById(id: string): Promise<ConversationRecord | null> {
    const conv = this.conversations.get(id);
    return conv ? this.toRecord(conv) : null;
  }

  async findUserMessageByClientTurnId(
    conversationId: string,
    clientTurnId: string,
  ): Promise<MessageRecord | null> {
    const found = this.messages.find(
      (m) =>
        m.conversationId === conversationId &&
        m.clientTurnId === clientTurnId &&
        m.role === "user",
    );
    return found ? this.toMessageRecord(found) : null;
  }

  async findFirstAssistantAfter(
    conversationId: string,
    afterCreatedAt: Date,
  ): Promise<MessageRecord | null> {
    const candidates = this.messages
      .filter(
        (m) =>
          m.conversationId === conversationId &&
          m.role === "assistant" &&
          m.createdAt.getTime() > afterCreatedAt.getTime(),
      )
      .sort((a, b) =>
        a.createdAt.getTime() - b.createdAt.getTime() || a.seq - b.seq,
      );
    return candidates[0] ? this.toMessageRecord(candidates[0]) : null;
  }

  async appendUserMessage(
    conversationId: string,
    content: string,
    clientTurnId: string,
  ): Promise<MessageRecord> {
    const duplicate = this.messages.some(
      (m) =>
        m.conversationId === conversationId && m.clientTurnId === clientTurnId,
    );
    if (duplicate) throw new P2002Error();

    const msg: StoredMessage = {
      id: randomUUID(),
      conversationId,
      role: "user",
      content,
      promptVersion: null,
      clientTurnId,
      createdAt: this.nextTimestamp(),
      seq: this.seq++,
    };
    this.messages.push(msg);
    return this.toMessageRecord(msg);
  }

  async loadRecentMessages(
    conversationId: string,
    limit: number,
  ): Promise<MessageRecord[]> {
    const ordered = this.messages
      .filter((m) => m.conversationId === conversationId)
      .sort((a, b) =>
        a.createdAt.getTime() - b.createdAt.getTime() || a.seq - b.seq,
      );
    return ordered.slice(-limit).map((m) => this.toMessageRecord(m));
  }

  async persistTurn(input: PersistTurnInput): Promise<MessageRecord> {
    if (this.failPersistForConversationId === input.conversationId) {
      // Mirror a transaction failure: NOTHING is written.
      throw new Error("Simulated DB failure during persistTurn");
    }

    const conv = this.conversations.get(input.conversationId);
    if (!conv) throw new Error("Conversation not found in persistTurn");

    const msg: StoredMessage = {
      id: randomUUID(),
      conversationId: input.conversationId,
      role: "assistant",
      content: input.assistantMessage,
      promptVersion: input.promptVersion,
      clientTurnId: null,
      createdAt: this.nextTimestamp(),
      seq: this.seq++,
    };
    this.messages.push(msg);

    conv.state = structuredClone(input.state);
    conv.stateVersion = input.stateVersion;
    if (input.close) conv.status = "closed";
    conv.updatedAt = this.nextTimestamp();

    return this.toMessageRecord(msg);
  }

  // --- test helpers ---

  countMessages(conversationId: string): number {
    return this.messages.filter((m) => m.conversationId === conversationId)
      .length;
  }

  countUserMessages(conversationId: string, clientTurnId: string): number {
    return this.messages.filter(
      (m) =>
        m.conversationId === conversationId &&
        m.role === "user" &&
        m.clientTurnId === clientTurnId,
    ).length;
  }

  private toRecord(conv: StoredConversation): ConversationRecord {
    return {
      id: conv.id,
      status: conv.status,
      state: structuredClone(conv.state),
      stateVersion: conv.stateVersion,
      experienceProfileId: conv.experienceProfileId,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
    };
  }

  private toMessageRecord(m: StoredMessage): MessageRecord {
    return {
      id: m.id,
      conversationId: m.conversationId,
      role: m.role,
      content: m.content,
      promptVersion: m.promptVersion,
      clientTurnId: m.clientTurnId,
      createdAt: m.createdAt,
    };
  }
}

/**
 * Deterministic fake provider. Always asks a question (contains "?") so that
 * questionCount increments by 1 per turn, letting the test drive the real
 * MAX_QUESTIONS boundary + closing lifecycle without any LLM.
 */
class FakeProvider implements LLMProvider {
  public calls = 0;
  constructor(
    private readonly makeOutput: (input: TurnInput, call: number) => TurnOutput =
      () => ({ assistantMessage: "Can you tell me more about that?" }),
  ) {}

  async generateTurn(input: TurnInput): Promise<TurnOutput> {
    this.calls += 1;
    return this.makeOutput(input, this.calls);
  }
}

/** A provider that blocks until released, for concurrency testing. */
function makeGatedProvider() {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const provider: LLMProvider = {
    generateTurn: async () => {
      await gate;
      return { assistantMessage: "Gated response?" };
    },
  };
  return { provider, release };
}

describe("M8 deterministic full-stack integration (fake provider + in-memory repo)", () => {
  let repo: InMemoryRepository;

  // The service is typed against the concrete ConversationRepository. The
  // in-memory fake implements the same method surface; cast at the injection
  // boundary exactly as the existing unit tests do.
  const asRepo = (r: InMemoryRepository) =>
    r as unknown as ConversationRepository;

  beforeEach(() => {
    repo = new InMemoryRepository();
  });

  it("progresses active -> wrap_up -> closing turn -> closed over multiple turns", async () => {
    const service = new ConversationService({
      repository: asRepo(repo),
      provider: new FakeProvider(),
      promptVersion: "1.0.0",
    });

    const conv = await repo.create();
    expect(conv.status).toBe("active");
    expect(conv.state.phase).toBe("intro");

    let sawWrapUp = false;
    let closingResult: Awaited<ReturnType<typeof service.processTurn>> | null =
      null;
    let status: "active" | "closed" = "active";

    // Bounded loop: each turn asks one question, so wrap_up is forced once
    // questionCount reaches MAX_QUESTIONS, then the following turn closes.
    for (let i = 0; i < MAX_QUESTIONS + 3 && status === "active"; i++) {
      const result = await service.processTurn({
        conversationId: conv.id,
        clientTurnId: `turn-${i}`,
        message: `Answer ${i}`,
      });
      if (result.state.phase === "wrap_up") sawWrapUp = true;
      status = result.status;
      if (result.closing) closingResult = result;
    }

    expect(sawWrapUp).toBe(true);
    expect(status).toBe("closed");
    expect(closingResult).not.toBeNull();
    expect(closingResult!.closing).toBe(true);
    expect(closingResult!.status).toBe("closed");

    const persisted = await repo.findById(conv.id);
    expect(persisted?.status).toBe("closed");
    // questionCount hit the boundary.
    expect(persisted!.state.questionCount).toBeGreaterThanOrEqual(MAX_QUESTIONS);
  });

  it("rejects a turn once the conversation is closed (409-equivalent)", async () => {
    const service = new ConversationService({
      repository: asRepo(repo),
      provider: new FakeProvider(),
      promptVersion: "1.0.0",
    });
    const conv = await repo.create();

    let status: "active" | "closed" = "active";
    let i = 0;
    while (status === "active" && i < MAX_QUESTIONS + 3) {
      const r = await service.processTurn({
        conversationId: conv.id,
        clientTurnId: `t-${i}`,
        message: `Answer ${i}`,
      });
      status = r.status;
      i++;
    }
    expect(status).toBe("closed");

    await expect(
      service.processTurn({
        conversationId: conv.id,
        clientTurnId: "after-close",
        message: "One more?",
      }),
    ).rejects.toMatchObject({ name: "ConversationClosedError" });
  });

  it("idempotent replay: same clientTurnId does not duplicate messages and returns same answer", async () => {
    const provider = new FakeProvider((_i, call) => ({
      assistantMessage: `Answer for call ${call}?`,
    }));
    const service = new ConversationService({
      repository: asRepo(repo),
      provider,
      promptVersion: "1.0.0",
    });
    const conv = await repo.create();

    const first = await service.processTurn({
      conversationId: conv.id,
      clientTurnId: "dup-1",
      message: "I built a mobile app.",
    });
    const countAfterFirst = repo.countMessages(conv.id);

    const second = await service.processTurn({
      conversationId: conv.id,
      clientTurnId: "dup-1",
      message: "I built a mobile app.",
    });
    const countAfterSecond = repo.countMessages(conv.id);

    // Replay returns the stored assistant message and adds no rows.
    expect(second.assistantMessage).toBe(first.assistantMessage);
    expect(countAfterSecond).toBe(countAfterFirst);
    expect(repo.countUserMessages(conv.id, "dup-1")).toBe(1);
    // The provider was only called once (replay short-circuits before the LLM).
    expect(provider.calls).toBe(1);
  });

  it("concurrency: overlapping turns for the same conversation -> exactly one succeeds", async () => {
    const { provider, release } = makeGatedProvider();
    const service = new ConversationService({
      repository: asRepo(repo),
      provider,
    });
    const conv = await repo.create();

    const p1 = service.processTurn({
      conversationId: conv.id,
      clientTurnId: "c-A",
      message: "Answer A",
    });
    const p2 = service.processTurn({
      conversationId: conv.id,
      clientTurnId: "c-B",
      message: "Answer B",
    });

    const settledSecond = await Promise.allSettled([p2]);
    // p2 should have been rejected synchronously by the in-process guard while
    // p1 is still gated.
    expect(settledSecond[0].status).toBe("rejected");
    expect(
      (settledSecond[0] as PromiseRejectedResult).reason,
    ).toBeInstanceOf(TurnInProgressError);

    release();
    const r1 = await p1;
    expect(r1.assistantMessage).toBe("Gated response?");

    // Exactly one user message was appended (from the winning turn).
    expect(repo.countMessages(conv.id)).toBe(2); // 1 user + 1 assistant
  });

  it("persistence consistency after a successful turn and after an idempotent replay", async () => {
    const service = new ConversationService({
      repository: asRepo(repo),
      provider: new FakeProvider(),
      promptVersion: "1.0.0",
    });
    const conv = await repo.create();

    await service.processTurn({
      conversationId: conv.id,
      clientTurnId: "p-1",
      message: "First answer",
    });

    const afterTurn = await repo.findById(conv.id);
    expect(afterTurn!.stateVersion).toBe(2); // 1 -> 2
    expect(afterTurn!.state.questionCount).toBe(1);
    const rowsAfterTurn = repo.countMessages(conv.id);
    expect(rowsAfterTurn).toBe(2); // user + assistant

    // Replay must not change persisted state or row count.
    await service.processTurn({
      conversationId: conv.id,
      clientTurnId: "p-1",
      message: "First answer",
    });
    const afterReplay = await repo.findById(conv.id);
    expect(afterReplay!.stateVersion).toBe(2);
    expect(afterReplay!.state.questionCount).toBe(1);
    expect(repo.countMessages(conv.id)).toBe(rowsAfterTurn);
  });

  it("atomic persist failure: state unchanged, no assistant row, user message remains", async () => {
    const service = new ConversationService({
      repository: asRepo(repo),
      provider: new FakeProvider(),
      promptVersion: "1.0.0",
    });
    const conv = await repo.create();
    repo.failPersistForConversationId = conv.id;

    await expect(
      service.processTurn({
        conversationId: conv.id,
        clientTurnId: "fail-1",
        message: "This turn fails to persist",
      }),
    ).rejects.toThrow(/Simulated DB failure/);

    const after = await repo.findById(conv.id);
    // State untouched (still initial), no assistant row, user message persisted
    // (it was committed before the LLM call, per the M1-M6 contract).
    expect(after!.status).toBe("active");
    expect(after!.stateVersion).toBe(1);
    expect(after!.state.questionCount).toBe(0);
    expect(repo.countMessages(conv.id)).toBe(1); // only the user message
    expect(repo.countUserMessages(conv.id, "fail-1")).toBe(1);
  });
});
