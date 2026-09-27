import type { ConversationState } from "./state/schema.js";
import { validateProposal } from "./state/validation.js";
import { mergeState } from "./state/merge.js";
import type { ConversationRepository, MessageRecord } from "./repository.js";
import type { LLMProvider, TranscriptMessage } from "./provider/index.js";
import { buildSystemPrompt, PROMPT_VERSION } from "./prompts/index.js";
import type { ExperienceProfile } from "../experience/schema.js";
import type { PracticeContextItem, PracticeContextReader } from "./practiceContext.js";

const CONTEXT_WINDOW = 12;

export class ConversationNotFoundError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Conversation not found: ${conversationId}`);
    this.name = "ConversationNotFoundError";
  }
}

export class ConversationClosedError extends Error {
  constructor(public readonly conversationId: string) {
    super(`Conversation is closed: ${conversationId}`);
    this.name = "ConversationClosedError";
  }
}

export class ProviderError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "ProviderError";
  }
}

export class ProviderTimeoutError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "ProviderTimeoutError";
  }
}

export class TurnInProgressError extends Error {
  constructor(public readonly conversationId: string) {
    super(`A turn is already in progress for conversation: ${conversationId}`);
    this.name = "TurnInProgressError";
  }
}

export class ExperienceProfileNotFoundError extends Error {
  constructor(public readonly experienceProfileId: string) {
    super(`Experience profile not found: ${experienceProfileId}`);
    this.name = "ExperienceProfileNotFoundError";
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

export interface TurnRequest {
  conversationId: string;
  clientTurnId: string;
  message: string;
}

export interface TurnResult {
  assistantMessage: string;
  state: ConversationState;
  status: "active" | "closed";
  closing: boolean;
}

/**
 * Minimal read boundary the service needs to resolve an experience profile.
 * Kept as a narrow interface so the service is decoupled from the concrete
 * ExperienceProfileRepository (and easy to fake in tests).
 */
export interface ExperienceProfileReader {
  findById(id: string): Promise<ExperienceProfile | null>;
}

export interface CreateConversationRequest {
  experienceProfileId?: string | null;
}

export interface CreateConversationResult {
  id: string;
  status: "active" | "closed";
  state: ConversationState;
}

export interface ServiceDependencies {
  repository: ConversationRepository;
  provider: LLMProvider;
  promptVersion?: string;
  experienceProfiles?: ExperienceProfileReader;
  /**
   * M20 optional enrichment, read through the conversation-side port in
   * `practiceContext.ts` and implemented by the M19 derivation module — same
   * direction and same optionality as `experienceProfiles`, so this module never
   * names a derivation type or imports that module (§7, §18.7). The
   * implementation never throws, which is why there is no try/catch around the
   * read (§17.3).
   */
  practiceContext?: PracticeContextReader;
}

export class ConversationService {
  private readonly activeTurns = new Set<string>();

  constructor(private readonly deps: ServiceDependencies) {}

  /**
   * Creates a conversation, optionally associated with an existing experience
   * profile. Creation goes through the service (not the route) so profile
   * validation/association is orchestration, not HTTP logic.
   */
  async createConversation(
    request: CreateConversationRequest = {},
  ): Promise<CreateConversationResult> {
    const experienceProfileId = request.experienceProfileId ?? null;

    if (experienceProfileId) {
      const reader = this.deps.experienceProfiles;
      const profile = reader
        ? await reader.findById(experienceProfileId)
        : null;
      if (!profile) {
        throw new ExperienceProfileNotFoundError(experienceProfileId);
      }
    }

    const conversation = await this.deps.repository.create(experienceProfileId);
    return {
      id: conversation.id,
      status: conversation.status,
      state: conversation.state,
    };
  }

  async processTurn(request: TurnRequest): Promise<TurnResult> {
    const conversationId = request.conversationId;

    if (this.activeTurns.has(conversationId)) {
      throw new TurnInProgressError(conversationId);
    }
    this.activeTurns.add(conversationId);

    try {
      return await this.executeTurn(request);
    } finally {
      this.activeTurns.delete(conversationId);
    }
  }

  /**
   * Read-only replay lookup (§7.B): returns the persisted reply for an
   * already-appended user message. It must never write or reach the provider,
   * which is why the closed rejection is allowed to follow it.
   */
  private async findReplay(
    conversationId: string,
    userMessage: MessageRecord | null,
    status: "active" | "closed",
    state: ConversationState,
  ): Promise<TurnResult | null> {
    if (!userMessage) return null;

    const existingAssistant = await this.deps.repository.findFirstAssistantAfter(
      conversationId,
      userMessage.createdAt,
    );
    if (!existingAssistant) return null;

    return {
      assistantMessage: existingAssistant.content,
      state,
      status,
      closing: status === "closed",
    };
  }

  private async executeTurn(request: TurnRequest): Promise<TurnResult> {
    const { repository, provider } = this.deps;
    const promptVersion = this.deps.promptVersion ?? PROMPT_VERSION;
    const conversationId = request.conversationId;

    const conversation = await repository.findById(conversationId);
    if (!conversation) {
      throw new ConversationNotFoundError(request.conversationId);
    }

    const loadedStatus: "active" | "closed" = conversation.status;

    const currentState = conversation.state;
    const phaseAtStart = currentState.phase;
    const isClosingTurn = phaseAtStart === "wrap_up";

    // M21 §7.B: the read-only idempotency probe is recognised before the closed
    // rejection, so re-submitting the closing turn replays the persisted reply
    // instead of being rejected. The probe writes nothing, so the rejection below
    // still precedes every write (C-02, C-06).
    let existingUserMessage = await repository.findUserMessageByClientTurnId(
      request.conversationId,
      request.clientTurnId,
    );

    const replay = await this.findReplay(
      request.conversationId,
      existingUserMessage,
      loadedStatus,
      currentState,
    );
    if (replay) return replay;

    if (loadedStatus === "closed") {
      throw new ConversationClosedError(request.conversationId);
    }

    if (!existingUserMessage) {
      try {
        await repository.appendUserMessage(
          request.conversationId,
          request.message,
          request.clientTurnId,
        );
      } catch (err: unknown) {
        if (!isUniqueConstraintError(err)) throw err;
        existingUserMessage = await repository.findUserMessageByClientTurnId(
          request.conversationId,
          request.clientTurnId,
        );
        const racedReplay = await this.findReplay(
          request.conversationId,
          existingUserMessage,
          loadedStatus,
          currentState,
        );
        if (racedReplay) return racedReplay;
      }
    }

    const allMessages = await repository.loadRecentMessages(
      request.conversationId,
      CONTEXT_WINDOW,
    );

    const transcript: TranscriptMessage[] = allMessages.map((m) => ({
      role: m.role,
      content: m.content,
    }));

    let experience: ExperienceProfile | undefined;
    if (conversation.experienceProfileId && this.deps.experienceProfiles) {
      experience =
        (await this.deps.experienceProfiles.findById(
          conversation.experienceProfileId,
        )) ?? undefined;
    }

    // M20: bounded Practice Context for the same experience profile. Omitted in
    // wrap_up, where the hints must never compete with closing (§16.4). An empty
    // selection stays `undefined` so the prompt is byte-identical to pre-M20
    // (§14.8). Read outside `persistTurn` and outside the provider call — it
    // writes nothing (§17.1, AC-28).
    let practiceContext: PracticeContextItem[] | undefined;
    if (
      conversation.experienceProfileId &&
      this.deps.practiceContext &&
      !isClosingTurn
    ) {
      const items = await this.deps.practiceContext.findForExperienceProfile(
        conversation.experienceProfileId,
      );
      if (items.length > 0) practiceContext = items;
    }

    const systemPrompt = buildSystemPrompt({
      state: currentState,
      transcript,
      experience,
      practiceContext,
    });

    let turnOutput;
    try {
      turnOutput = await provider.generateTurn({
        systemPrompt,
        state: currentState,
        messages: transcript,
      });
    } catch (err) {
      if (err instanceof ProviderTimeoutError) throw err;
      throw new ProviderError("LLM provider failed", err);
    }

    if (
      !turnOutput ||
      typeof turnOutput.assistantMessage !== "string" ||
      turnOutput.assistantMessage.length === 0
    ) {
      throw new ProviderError("Malformed LLM response: no assistant message");
    }

    const validation = validateProposal(turnOutput.proposal, currentState);

    const nextState = mergeState(
      currentState,
      validation.valid ? validation.proposal : null,
      turnOutput.assistantMessage,
    );

    const nextStateVersion = conversation.stateVersion + 1;

    await repository.persistTurn({
      conversationId: request.conversationId,
      assistantMessage: turnOutput.assistantMessage,
      promptVersion,
      state: nextState,
      stateVersion: nextStateVersion,
      close: isClosingTurn,
    });

    return {
      assistantMessage: turnOutput.assistantMessage,
      state: nextState,
      status: isClosingTurn ? "closed" : "active",
      closing: isClosingTurn,
    };
  }
}
