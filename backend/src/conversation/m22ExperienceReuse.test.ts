import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  ConversationService,
  ExperienceProfileNotFoundError,
} from "./service.js";
import type {
  ConversationRecord,
  ConversationRepository,
  MessageRecord,
} from "./repository.js";
import type { ExperienceProfileReader } from "./service.js";
import type { LLMProvider } from "./provider/index.js";
import type { PracticeContextItem, PracticeContextReader } from "./practiceContext.js";
import { INITIAL_STATE } from "./state/schema.js";
import type { ExperienceProfile } from "../experience/schema.js";

/**
 * M22 Track B — Experience Reuse / Practice Continuity (Contract v0.1 §8–§14,
 * AC-M22-14…AC-M22-40; tests T7–T16, T18, T19 of §21).
 *
 * M22 adds no endpoint, no table and no state field: "reuse" is `POST
 * /conversations` with an ALREADY-EXISTING `experienceProfileId`, which the
 * engine has accepted and validated since M9. These tests pin the server half
 * of the continuity guarantees the frontend reuse affordance depends on, so the
 * browser-local pointer can never become a second source of truth.
 *
 * The invariant that carries M22: an interview's continuity is a function of
 * `experienceProfileId` ALONE. Same profile ⇒ same PracticeTrack ⇒ same M20
 * practice context (AC-M22-04/06/14/15/20). A different profile ⇒ a
 * different key ⇒ nothing from the previous experience can follow it
 * (AC-M22-05, AC-M22-21…AC-M22-24).
 */

const OLD_PROFILE = "prof-old";
const NEW_PROFILE = "prof-new";

function profile(id: string): ExperienceProfile {
  return {
    id,
    createdAt: new Date(),
    updatedAt: new Date(),
    items: [
      {
        id: `item-${id}`,
        title: `Project of ${id}`,
        organization: null,
        role: null,
        description: "A note taking app",
        skills: [],
        position: 0,
      },
    ],
  };
}

function practiceItem(): PracticeContextItem {
  return {
    dimension: "english",
    focus: "grammar",
    observation: "Tense agreement slips in longer answers.",
    practiceCue: "Retell one accomplishment entirely in present perfect.",
  };
}

interface Harness {
  service: ConversationService;
  repo: ConversationRepository;
  profiles: { findById: ReturnType<typeof vi.fn> };
  context: { findForExperienceProfile: ReturnType<typeof vi.fn> };
  provider: LLMProvider;
  /** system prompt + transcript the provider saw, newest first entry = turn order */
  turns: Array<{ systemPrompt: string; messages: unknown[] }>;
  profileOf: (conversationId: string) => string | null;
  createCalls: (string | null)[];
}

function setup(options: {
  knownProfiles?: string[];
  practiceItems?: (profileId: string) => PracticeContextItem[];
} = {}): Harness {
  const known = new Set(options.knownProfiles ?? [OLD_PROFILE, NEW_PROFILE]);
  const owners = new Map<string, string | null>();
  const transcripts = new Map<string, MessageRecord[]>();
  const createCalls: (string | null)[] = [];
  const turns: Array<{ systemPrompt: string; messages: unknown[] }> = [];
  let clock = 0;
  let seq = 0;

  const record = (id: string): MessageRecord => ({
    id,
    conversationId: "conv",
    role: "user",
    content: "placeholder",
    promptVersion: null,
    clientTurnId: null,
    createdAt: new Date(Date.UTC(2026, 0, 1, 0, 0, clock++)),
  });

  const repo = {
    async create(experienceProfileId: string | null): Promise<ConversationRecord> {
      createCalls.push(experienceProfileId ?? null);
      seq += 1;
      const id = `conv-${seq}`;
      owners.set(id, experienceProfileId ?? null);
      transcripts.set(id, []);
      return {
        id,
        status: "active",
        state: INITIAL_STATE,
        stateVersion: 1,
        experienceProfileId: experienceProfileId ?? null,
        createdAt: new Date(),
        updatedAt: new Date(),
      } satisfies ConversationRecord;
    },
    async findById(id: string): Promise<ConversationRecord | null> {
      if (!owners.has(id)) return null;
      return {
        id,
        status: "active",
        state: INITIAL_STATE,
        stateVersion: 1,
        experienceProfileId: owners.get(id) ?? null,
        createdAt: new Date(),
        updatedAt: new Date(),
      };
    },
    findUserMessageByClientTurnId: vi.fn().mockResolvedValue(null),
    findFirstAssistantAfter: vi.fn().mockResolvedValue(null),
    async appendUserMessage(
      conversationId: string,
      content: string,
      clientTurnId: string,
    ): Promise<MessageRecord> {
      const row: MessageRecord = {
        ...record(`msg-${conversationId}-${clientTurnId}`),
        conversationId,
        content,
        clientTurnId,
      };
      transcripts.set(conversationId, [
        ...(transcripts.get(conversationId) ?? []),
        row,
      ]);
      return row;
    },
    async loadRecentMessages(conversationId: string): Promise<MessageRecord[]> {
      return transcripts.get(conversationId) ?? [];
    },
    async loadAllMessages(conversationId: string): Promise<MessageRecord[]> {
      return transcripts.get(conversationId) ?? [];
    },
    async persistTurn(input: {
      conversationId: string;
      assistantMessage: string;
    }): Promise<MessageRecord> {
      const row: MessageRecord = {
        ...record(`msg-${input.conversationId}-assistant`),
        conversationId: input.conversationId,
        role: "assistant",
        content: input.assistantMessage,
        promptVersion: "2.2.0",
      };
      transcripts.set(input.conversationId, [
        ...(transcripts.get(input.conversationId) ?? []),
        row,
      ]);
      return row;
    },
  } as unknown as ConversationRepository;

  const profiles = {
    findById: vi.fn(async (id: string) => (known.has(id) ? profile(id) : null)),
  };
  const context = {
    findForExperienceProfile: vi.fn(async (id: string) =>
      (options.practiceItems ?? (() => [practiceItem()]))(id),
    ),
  };
  const provider = {
    generateTurn: vi.fn(async (input: { systemPrompt: string; messages: unknown[] }) => {
      turns.push({ systemPrompt: input.systemPrompt, messages: input.messages });
      return {
        assistantMessage: "Tell me about your role in it?",
        proposal: null,
      };
    }),
  } as unknown as LLMProvider;

  const service = new ConversationService({
    repository: repo,
    provider,
    experienceProfiles: profiles as unknown as ExperienceProfileReader,
    practiceContext: context as unknown as PracticeContextReader,
  });

  return {
    service,
    repo,
    profiles,
    context,
    provider,
    turns,
    profileOf: (id) => owners.get(id) ?? null,
    createCalls,
  };
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe("M22 T7–T10 — reuse resolves the existing profile and a fresh conversation (AC-M22-14…AC-M22-18)", () => {
  it("attaches the EXISTING profile by identity, creating no profile of its own", async () => {
    const h = setup();

    const reused = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });

    expect(reused.state).toEqual(INITIAL_STATE);
    expect(reused.status).toBe("active");
    expect(vi.mocked(h.profiles.findById)).toHaveBeenCalledWith(OLD_PROFILE);
    expect(h.createCalls).toEqual([OLD_PROFILE]);
    expect(h.profileOf(reused.id)).toBe(OLD_PROFILE);
  });

  it("two interviews on one experience are two conversations, each starting with fresh state and an empty transcript (AC-M22-01, AC-M22-16…AC-M22-18)", async () => {
    const h = setup();

    const first = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });
    await h.service.processTurn({
      conversationId: first.id,
      clientTurnId: "turn-a",
      message: "I led the frontend of a note taking app.",
    });

    const second = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });
    await h.service.processTurn({
      conversationId: second.id,
      clientTurnId: "turn-b",
      message: "Hi.",
    });

    expect(second.id).not.toBe(first.id);
    // The second interview's provider call sees ONLY its own new user message —
    // the first interview's transcript is nowhere in it (AC-M22-03, AC-M22-24).
    expect(h.turns[1].messages).toEqual([
      expect.objectContaining({ role: "user", content: "Hi." }),
    ]);
    expect(
      JSON.stringify(h.turns[1].messages).includes(
        "I led the frontend of a note taking app.",
      ),
    ).toBe(false);
    // Fresh operational state: the counting restarts from zero.
    expect(h.turns[1].systemPrompt).toContain("- Questions asked so far: 0");
    expect(h.turns[1].systemPrompt).toContain("- Phase: intro");
  });
});

describe("M22 T12 — reuse reaches the same PracticeTrack context through the existing M20 mechanism (AC-M22-20, AC-M22-36, AC-M22-40)", () => {
  it("reads practice context by experienceProfileId alone, once per turn, with no second selector", async () => {
    const h = setup();

    const first = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });
    const second = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });

    await h.service.processTurn({
      conversationId: first.id,
      clientTurnId: "turn-1",
      message: "I led the frontend.",
    });
    await h.service.processTurn({
      conversationId: second.id,
      clientTurnId: "turn-1",
      message: "I led the frontend.",
    });

    // Same key ⇒ same bounded context: the reused experience stays eligible.
    expect(vi.mocked(h.context.findForExperienceProfile).mock.calls).toEqual([
      [OLD_PROFILE],
      [OLD_PROFILE],
    ]);
    expect(h.turns[0].systemPrompt).toContain("PRACTICE CONTEXT (BEGIN)");
    expect(h.turns[1].systemPrompt).toContain("PRACTICE CONTEXT (BEGIN)");
  });

  it("carries only what M20 allows into the reused interview — no raw feedback, no retry, no transcript (AC-M22-37…AC-M22-39)", async () => {
    const h = setup();

    const created = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });
    await h.service.processTurn({
      conversationId: created.id,
      clientTurnId: "turn-1",
      message: "I led the frontend.",
    });
    const prompt = h.turns[0].systemPrompt;

    expect(prompt).toContain("Focus: grammar");
    expect(prompt).toContain("Practise: Retell one accomplishment");
    for (const forbidden of [
      "answerMessageId",
      "questionMessageId",
      "whatWorked",
      "couldImprove",
      "retryAnswer",
      "derivationId",
      "practiceTrackId",
    ]) {
      expect(prompt).not.toContain(forbidden);
    }
  });
});

describe("M22 T13–T16 — Start New isolation (AC-M22-05, AC-M22-21…AC-M22-24)", () => {
  it("a new experience is a different key, so it gets its own context read and inherits nothing", async () => {
    const h = setup({
      practiceItems: (profileId) =>
        profileId === OLD_PROFILE ? [practiceItem()] : [],
    });

    const previous = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });
    const fresh = await h.service.createConversation({
      experienceProfileId: NEW_PROFILE,
    });

    await h.service.processTurn({
      conversationId: previous.id,
      clientTurnId: "turn-1",
      message: "I led the frontend.",
    });
    await h.service.processTurn({
      conversationId: fresh.id,
      clientTurnId: "turn-1",
      message: "I led the frontend.",
    });

    expect(vi.mocked(h.context.findForExperienceProfile).mock.calls).toEqual([
      [OLD_PROFILE],
      [NEW_PROFILE],
    ]);
    // The Start New interview renders no practice context and grounds on its own
    // profile only: nothing from the previous experience followed it.
    expect(h.turns[1].systemPrompt).not.toContain("PRACTICE CONTEXT");
    expect(h.turns[1].systemPrompt).toContain("Project of prof-new");
    expect(h.turns[1].systemPrompt).not.toContain("Project of prof-old");
  });

  it("reads no practice context at all for an interview without a profile", async () => {
    const h = setup();

    const anonymous = await h.service.createConversation({});
    await h.service.processTurn({
      conversationId: anonymous.id,
      clientTurnId: "turn-1",
      message: "I led the frontend.",
    });

    expect(h.createCalls).toEqual([null]);
    expect(h.context.findForExperienceProfile).not.toHaveBeenCalled();
    expect(h.turns[0].systemPrompt).not.toContain("PRACTICE CONTEXT");
  });
});

describe("M22 T18/T19 — an unresolvable reference fails safely (AC-M22-33, AC-M22-34)", () => {
  it("rejects an unknown profile id and creates NOTHING, rather than attaching another experience", async () => {
    const h = setup({ knownProfiles: [OLD_PROFILE] });

    await expect(
      h.service.createConversation({ experienceProfileId: "prof-ghost" }),
    ).rejects.toThrow(ExperienceProfileNotFoundError);

    expect(h.createCalls).toEqual([]);
    expect(h.provider.generateTurn).not.toHaveBeenCalled();
  });

  it("leaves a valid reference usable after an unrelated one failed", async () => {
    const h = setup({ knownProfiles: [OLD_PROFILE] });

    await expect(
      h.service.createConversation({ experienceProfileId: "prof-ghost" }),
    ).rejects.toThrow(ExperienceProfileNotFoundError);

    const reused = await h.service.createConversation({
      experienceProfileId: OLD_PROFILE,
    });
    expect(reused.state).toEqual(INITIAL_STATE);
    expect(h.createCalls).toEqual([OLD_PROFILE]);
  });
});
