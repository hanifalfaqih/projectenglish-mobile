import type { ConversationState } from "../state/schema.js";

export interface TurnInput {
  systemPrompt: string;
  state: ConversationState;
  messages: TranscriptMessage[];
}

export interface TranscriptMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface TurnOutput {
  assistantMessage: string;
  proposal?: unknown;
}

export interface LLMProvider {
  generateTurn(input: TurnInput): Promise<TurnOutput>;
}
