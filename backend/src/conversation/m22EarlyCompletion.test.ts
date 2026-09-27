import { describe, it, expect, vi, beforeEach } from "vitest";
import { ConversationService } from "./service.js";
import type {
  ConversationRecord,
  ConversationRepository,
  MessageRecord,
} from "./repository.js";
import type { LLMProvider } from "./provider/index.js";
import { mergeState } from "./state/merge.js";
import { validateProposal } from "./state/validation.js";
import type { ConversationState } from "./state/schema.js";
import { PROMPT_VERSION } from "./prompts/index.js";

/**
 * M22 Track A — Conversational Early Completion (Contract v0.1 §5–§7,
 * AC-M22-01…AC-M22-13; tests T1–T6 of §21).
 *
 * What M22 actually changed is ONE thing: the interviewer is now instructed
 * that a learner may say they are out of material, and that it may then propose
 * the existing `wrap_up` phase early (§7 prompt block, prompt version bump).
 * Everything below that instruction is the locked Conversation Engine state
 * machine, which M22 must not touch (QWEN.md §9, AC-M22-02/04/06/09, INV-09).
 * These tests pin BOTH halves: the instruction reaches the provider, and the
 * pre-existing mechanics still decide what a proposal is allowed to do.
 *
 * Recognition quality itself is real-provider behaviour. Nothing here claims it
 * (Contract §23: never claim live verification from mocked tests).
 */

const COMPLETION_BLOCK =
  "Recognising a candidate who has finished contributing:";

function makeConversation(
  overrides: Partial<ConversationRecord> = {},
): ConversationRecord {
  return {
    id: "conv-1",
    status: "active",
    state: {
      phase: "deep_dive",
      topics: [{ id: "t1", label: "mobile app", covered: false }],
      currentTopicId: "t1",
      questionCount: 3,
    },
    stateVersion: 4,
    experienceProfileId: "prof-1",
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides,
  };
}

function makeMessage(overrides: Partial<MessageRecord> = {}): MessageRecord {
  return {
    id: "msg-1",
    conversationId: "conv-1",
    role: "user",
    content: "I built a mobile app.",
    promptVersion: null,
    clientTurnId: "turn-1",
    createdAt: new Date(),
    ...overrides,
  };
}

function stateOf(overrides: Partial<ConversationState> = {}): ConversationState {
  return {
    phase: "deep_dive",
    topics: [{ id: "t1", label: "mobile app", covered: false }],
    currentTopicId: "t1",
    questionCount: 3,
    ...overrides,
  };
}

interface Harness {
  repo: ConversationRepository;
  provider: LLMProvider;
  service: ConversationService;
  /** Queue the interviewer replies, one per `run` call, in order. */
  run: (
    message: string,
    reply: { assistantMessage: string; proposal?: unknown },
    clientTurnId?: string,
  ) => Promise<{
    result: Awaited<ReturnType<ConversationService["processTurn"]>>;
    systemPrompt: string;
  }>;
}

function setup(): Harness {
  const repo = {
    findById: vi.fn(),
    findUserMessageByClientTurnId: vi.fn().mockResolvedValue(null),
    findFirstAssistantAfter: vi.fn().mockResolvedValue(null),
    appendUserMessage: vi.fn().mockResolvedValue(makeMessage()),
    loadRecentMessages: vi.fn().mockResolvedValue([makeMessage()]),
    loadAllMessages: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    persistTurn: vi.fn().mockResolvedValue(makeMessage({ role: "assistant" })),
  } as unknown as ConversationRepository;

  const provider = { generateTurn: vi.fn() } as unknown as LLMProvider;
  const service = new ConversationService({ repository: repo, provider });
  let current: ConversationRecord = makeConversation();
  vi.mocked(repo.findById).mockImplementation(async () => current);

  return {
    repo,
    provider,
    service,
    async run(message, reply, clientTurnId = "turn-1") {
      vi.mocked(provider.generateTurn).mockResolvedValue(reply as never);
      const result = await service.processTurn({
        conversationId: "conv-1",
        clientTurnId,
        message,
      });
      const call = vi.mocked(provider.generateTurn).mock.calls.at(-1) as [
        { systemPrompt: string },
      ];
      // The authoritative state advances exactly as the engine merged it, so the
      // next `run` continues the same interview.
      current = makeConversation({
        status: result.status,
        state: result.state,
        stateVersion: current.stateVersion + 1,
      });
      return { result, systemPrompt: call[0].systemPrompt };
    },
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe("M22 T1 — a completion signal reaches the existing wrap-up path (AC-M22-01, AC-M22-02)", () => {
  it("accepts an early wrap_up proposal well below MAX_QUESTIONS and closes on the next turn", async () => {
    const h = setup();

    const signalled = await h.run(
      "That's everything about the project, I don't have anything else to add.",
      {
        assistantMessage:
          "Understood — thank you for walking me through that.",
        proposal: { phase: "wrap_up" },
      },
    );

    // Transition turn: the interviewer acknowledges, nothing closes yet.
    expect(signalled.result.state.phase).toBe("wrap_up");
    expect(signalled.result.state.questionCount).toBe(3);
    expect(signalled.result.status).toBe("active");
    expect(signalled.result.closing).toBe(false);

    // Closing turn: the pre-existing lifecycle performs the close.
    const closed = await h.run(
      "Thank you for the opportunity.",
      {
        assistantMessage: "Thank you, that concludes our interview.",
        proposal: null,
      },
      "turn-2",
    );

    expect(closed.result.status).toBe("closed");
    expect(closed.result.closing).toBe(true);
    expect(
      vi.mocked(h.repo.persistTurn).mock.calls.at(-1)![0],
    ).toMatchObject({ close: true, conversationId: "conv-1" });
  });

  it("is reached from EXPERIENCE as well as DEEP_DIVE, at any question count", async () => {
    for (const phase of ["experience", "deep_dive"] as const) {
      const h = setup();
      const { result } = await h.run("I have nothing more to contribute.", {
        assistantMessage: "Let me wrap up then.",
        proposal: { phase: "wrap_up" },
      });
      expect(stateOf({ phase }).phase).toBe(phase);
      expect(result.state.phase).toBe("wrap_up");
      expect(result.status).toBe("active");
    }
  });
});

describe("M22 T2/T3 — difficulty is not completion (AC-M22-07, AC-M22-09)", () => {
  for (const answer of [
    "I don't know.",
    "I'm not sure.",
    "Hmm.",
    "Can you repeat the question?",
    "Actually, I meant something different.",
    "I can't give much detail right now.",
  ]) {
    it(`keeps the interview open when the interviewer treats "${answer}" as ordinary difficulty`, async () => {
      const h = setup();

      const { result } = await h.run(answer, {
        assistantMessage: "No problem — what was your part of it?",
        proposal: { phase: "deep_dive" },
      });

      expect(result.status).toBe("active");
      expect(result.closing).toBe(false);
      expect(result.state.phase).toBe("deep_dive");
      expect(result.state.questionCount).toBe(4);
      expect(
        vi.mocked(h.repo.persistTurn).mock.calls.at(-1)![0].close,
      ).toBe(false);
    });
  }

  it("cannot be short-circuited from INTRO — the legal transition set still gates wrap_up", async () => {
    const merged = mergeState(
      stateOf({ phase: "intro", questionCount: 0, topics: [], currentTopicId: null }),
      validateProposal({ phase: "wrap_up" }, stateOf({ phase: "intro" })).proposal,
      "I have nothing to say.",
    );

    expect(merged.phase).toBe("intro");
  });

  it("cannot escape wrap_up backwards, so a signal is never un-done by a later proposal (INV-09)", () => {
    const wrapUp = stateOf({ phase: "wrap_up", questionCount: 4 });
    const merged = mergeState(
      wrapUp,
      validateProposal({ phase: "deep_dive" }, wrapUp).proposal,
      "Actually, let me add one more thing?",
    );

    expect(merged.phase).toBe("wrap_up");
  });
});

describe("M22 T4 — the existing completion lifecycle stays authoritative (AC-M22-02, AC-M22-04, AC-M22-06)", () => {
  it("closes only on a turn that STARTED in wrap_up, never on the turn that proposed it", async () => {
    const h = setup();

    const transition = await h.run("That's all I have.", {
      assistantMessage: "Thank you — I'll wrap up now.",
      proposal: { phase: "wrap_up" },
    });
    const closeCalls = vi
      .mocked(h.repo.persistTurn)
      .mock.calls.filter((c) => c[0].close === true);
    expect(closeCalls).toHaveLength(0);
    expect(transition.result.status).toBe("active");

    await h.run("Thanks!", { assistantMessage: "Good luck, bye!", proposal: null }, "turn-2");
    const after = vi
      .mocked(h.repo.persistTurn)
      .mock.calls.filter((c) => c[0].close === true);
    expect(after).toHaveLength(1);
  });

  it("persistTurn is the only writer, so there is no second close path (AC-M22-06)", async () => {
    const h = setup();

    await h.run("That's all I have.", {
      assistantMessage: "Thank you — I'll wrap up now.",
      proposal: { phase: "wrap_up" },
    });

    expect(vi.mocked(h.repo.appendUserMessage)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(h.repo.persistTurn)).toHaveBeenCalledTimes(1);
    expect(Object.keys(h.repo)).toEqual(
      expect.arrayContaining(["appendUserMessage", "persistTurn"]),
    );
  });

  it("MAX_QUESTIONS still forces wrap_up on its own — M22 did not replace it (INV-13)", () => {
    const merged = mergeState(
      stateOf({ questionCount: 7 }),
      validateProposal({ phase: "deep_dive" }, stateOf({ questionCount: 7 }))
        .proposal,
      "And what was the hardest part?",
    );

    expect(merged.questionCount).toBe(8);
    expect(merged.phase).toBe("wrap_up");
  });
});

describe("M22 T5 — an early-completed interview stays eligible for Feedback/Retry/Review (AC-M22-10)", () => {
  it("ends as a normally closed conversation through the shared write path", async () => {
    const h = setup();

    await h.run("That's everything I have.", {
      assistantMessage: "Let me close then.",
      proposal: { phase: "wrap_up" },
    });
    const closed = await h.run(
      "Thank you.",
      { assistantMessage: "Thank you — that's the interview.", proposal: null },
      "turn-2",
    );

    // "closed" is the single precondition Feedback (M11), Retry (M12) and Review
    // (M16) each check, so an early completion is indistinguishable to them.
    expect(closed.result.status).toBe("closed");
    expect(vi.mocked(h.repo.persistTurn).mock.calls.at(-1)![0].close).toBe(true);
    // No synthetic turn was appended to obtain the close (AC-M22-05).
    expect(vi.mocked(h.repo.appendUserMessage)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(h.repo.appendUserMessage).mock.calls.map((c) => c[2])).toEqual(
      ["turn-1", "turn-2"],
    );
  });
});

describe("M22 T6 — the prompt/version contract holds on the live turn path (AC-M22-11, AC-M22-12)", () => {
  it("sends the early-completion instruction on every turn and records the bumped version", async () => {
    const h = setup();

    const first = await h.run("I built a mobile app.", {
      assistantMessage: "What did you own in it?",
      proposal: null,
    });
    const second = await h.run("I have nothing more to add.", {
      assistantMessage: "Understood — let me close.",
      proposal: { phase: "wrap_up" },
    });

    expect(first.systemPrompt).toContain(COMPLETION_BLOCK);
    expect(second.systemPrompt).toContain(COMPLETION_BLOCK);
    expect(
      vi.mocked(h.repo.persistTurn).mock.calls.at(-1)![0].promptVersion,
    ).toBe(PROMPT_VERSION);
  });

  it("adds no extra LLM call for the completion turn (INV-12, AC-M22-53)", async () => {
    const h = setup();

    await h.run("That's all I have.", {
      assistantMessage: "Let me wrap up.",
      proposal: { phase: "wrap_up" },
    });
    await h.run("Thanks.", { assistantMessage: "Bye!", proposal: null }, "turn-2");

    expect(vi.mocked(h.provider.generateTurn)).toHaveBeenCalledTimes(2);
  });
});
