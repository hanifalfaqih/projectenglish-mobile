import type { Phase } from "./schema.js";
import { StateProposal } from "./schema.js";
import type { ConversationState } from "./schema.js";

const LEGAL_TRANSITIONS: Record<Phase, ReadonlySet<Phase>> = {
  intro: new Set(["intro", "experience"]),
  experience: new Set(["experience", "deep_dive", "wrap_up"]),
  deep_dive: new Set(["deep_dive", "experience", "wrap_up"]),
  wrap_up: new Set(["wrap_up"]),
};

export interface ValidationResult {
  valid: boolean;
  proposal: ReturnType<typeof StateProposal.parse> | null;
  error?: string;
}

export function validateProposal(
  raw: unknown,
  currentState: ConversationState,
): ValidationResult {
  const parsed = StateProposal.safeParse(raw);

  if (!parsed.success) {
    return { valid: false, proposal: null, error: parsed.error.message };
  }

  return { valid: true, proposal: parsed.data };
}

export function normalizeLabel(label: string): string {
  return label.trim().replace(/\s+/g, " ");
}

export function labelsEqual(a: string, b: string): boolean {
  return a.toLowerCase() === b.toLowerCase();
}

export function isLegalTransition(from: Phase, to: Phase): boolean {
  return LEGAL_TRANSITIONS[from].has(to);
}
