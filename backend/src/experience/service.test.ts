import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  ConversationService,
  ExperienceProfileNotFoundError,
  type ExperienceProfileReader,
} from "../conversation/service.js";
import type {
  ConversationRepository,
  ConversationRecord,
  MessageRecord,
} from "../conversation/repository.js";
import type { LLMProvider } from "../conversation/provider/index.js";
import type { ExperienceProfile } from "./schema.js";

function makeConversation(
  overrides: Partial<ConversationRecord> = {},
): ConversationRecord {
  return {
    id: "conv-1",
    status: "active",
    state: { phase: "intro", topics: [], currentTopicId: null, questionCount: 0 },
    stateVersion: 1,
    experienceProfileId: null,
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
    content: "Hello",
    promptVersion: null,
    clientTurnId: "turn-1",
    createdAt: new Date(),
    ...overrides,
  };
}

function makeProfile(overrides: Partial<ExperienceProfile> = {}): ExperienceProfile {
  return {
    id: "prof-1",
    items: [
      {
        id: "it-1",
        title: "Backend Intern",
        organization: "PT Example",
        role: "Developer",
        description: "Built APIs.",
        skills: ["TypeScript"],
        position: 0,
      },
    ],
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides,
  };
}

function createMockRepo(): ConversationRepository {
  return {
    create: vi.fn(),
    findById: vi.fn(),
    findUserMessageByClientTurnId: vi.fn(),
    findFirstAssistantAfter: vi.fn(),
    appendUserMessage: vi.fn(),
    loadRecentMessages: vi.fn(),
    persistTurn: vi.fn(),
  } as unknown as ConversationRepository;
}

function createMockProvider(): LLMProvider {
  return {
    generateTurn: vi.fn().mockResolvedValue({
      assistantMessage: "Tell me about your experience?",
    }),
  };
}

describe("ConversationService.createConversation (M9)", () => {
  let repo: ConversationRepository;
  let provider: LLMProvider;
  let experienceProfiles: ExperienceProfileReader;

  beforeEach(() => {
    repo = createMockRepo();
    provider = createMockProvider();
    experienceProfiles = { findById: vi.fn() };
  });

  it("creates a conversation with no profile (M8 behavior preserved)", async () => {
    vi.mocked(repo.create).mockResolvedValue(makeConversation());
    const service = new ConversationService({ repository: repo, provider, experienceProfiles });

    const result = await service.createConversation();

    expect(result.status).toBe("active");
    expect(repo.create).toHaveBeenCalledWith(null);
    expect(experienceProfiles.findById).not.toHaveBeenCalled();
  });

  it("creates a conversation associated with an existing profile", async () => {
    vi.mocked(experienceProfiles.findById).mockResolvedValue(makeProfile());
    vi.mocked(repo.create).mockResolvedValue(
      makeConversation({ experienceProfileId: "prof-1" }),
    );
    const service = new ConversationService({ repository: repo, provider, experienceProfiles });

    const result = await service.createConversation({ experienceProfileId: "prof-1" });

    expect(experienceProfiles.findById).toHaveBeenCalledWith("prof-1");
    expect(repo.create).toHaveBeenCalledWith("prof-1");
    expect(result.status).toBe("active");
  });

  it("rejects creation when the referenced profile does not exist", async () => {
    vi.mocked(experienceProfiles.findById).mockResolvedValue(null);
    const service = new ConversationService({ repository: repo, provider, experienceProfiles });

    await expect(
      service.createConversation({ experienceProfileId: "missing" }),
    ).rejects.toThrow(ExperienceProfileNotFoundError);

    expect(repo.create).not.toHaveBeenCalled();
  });

  it("rejects a profile reference when no experience reader is configured", async () => {
    // Defensive: association requested but reader missing -> treat as not found.
    const service = new ConversationService({ repository: repo, provider });

    await expect(
      service.createConversation({ experienceProfileId: "prof-1" }),
    ).rejects.toThrow(ExperienceProfileNotFoundError);
    expect(repo.create).not.toHaveBeenCalled();
  });
});

describe("ConversationService turn grounding (M9)", () => {
  let repo: ConversationRepository;
  let provider: LLMProvider;
  let experienceProfiles: ExperienceProfileReader;

  beforeEach(() => {
    repo = createMockRepo();
    provider = createMockProvider();
    experienceProfiles = { findById: vi.fn() };
  });

  it("loads the profile and includes it in the system prompt during a turn", async () => {
    vi.mocked(repo.findById).mockResolvedValue(
      makeConversation({ experienceProfileId: "prof-1" }),
    );
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([
      makeMessage({ role: "user", content: "Hi" }),
    ]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant", content: "Question?" }),
    );
    vi.mocked(experienceProfiles.findById).mockResolvedValue(makeProfile());

    const service = new ConversationService({ repository: repo, provider, experienceProfiles });

    await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hi",
    });

    expect(experienceProfiles.findById).toHaveBeenCalledWith("prof-1");
    const call = vi.mocked(provider.generateTurn).mock.calls[0][0];
    expect(call.systemPrompt).toContain("CANDIDATE EXPERIENCE PROFILE");
    expect(call.systemPrompt).toContain("Backend Intern");
  });

  it("does not load a profile when the conversation has none (M8 path)", async () => {
    vi.mocked(repo.findById).mockResolvedValue(makeConversation());
    vi.mocked(repo.findUserMessageByClientTurnId).mockResolvedValue(null);
    vi.mocked(repo.appendUserMessage).mockResolvedValue(makeMessage());
    vi.mocked(repo.loadRecentMessages).mockResolvedValue([]);
    vi.mocked(repo.persistTurn).mockResolvedValue(
      makeMessage({ role: "assistant", content: "Question?" }),
    );

    const service = new ConversationService({ repository: repo, provider, experienceProfiles });

    await service.processTurn({
      conversationId: "conv-1",
      clientTurnId: "turn-1",
      message: "Hi",
    });

    expect(experienceProfiles.findById).not.toHaveBeenCalled();
    const call = vi.mocked(provider.generateTurn).mock.calls[0][0];
    expect(call.systemPrompt).not.toContain("CANDIDATE EXPERIENCE PROFILE");
  });
});
