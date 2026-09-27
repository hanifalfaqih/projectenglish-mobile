/**
 * Actual M20 — the conversation-side Practice Context boundary (Contract v0.1 §7).
 *
 * Practice Context is TRANSIENT, derived, non-authoritative prompt input: at
 * most three qualitative practice areas the backend optionally appends to the
 * interview system prompt. It is not persisted, is not `ConversationState`, is
 * not a `StateProposal`, is not a Message, and is not a provider protocol (§7.1-§7.4).
 *
 * This file is the whole of the conversation side's knowledge about practice
 * context. The port is DECLARED here and IMPLEMENTED by the M19 derivation
 * module in its own `practiceContext.ts`, which is the shipped pattern for
 * optional enrichment (`ExperienceProfileReader`) and what keeps the dependency
 * direction one-way: `conversation/**` never imports the derivation module, not
 * even for a type (§18.7) — a property a test in this directory enforces.
 * `dimension`/`focus` are plain strings on purpose so the conversation module
 * cannot duplicate or re-derive the M19 taxonomy (§7.5, §10.2); the derivation
 * module guarantees the values came from the enum columns.
 */
export interface PracticeContextItem {
  dimension: string;
  focus: string;
  observation: string;
  practiceCue: string;
}

/**
 * The only derivation read the interview may perform. Implementations MUST fail
 * open: an empty array, never a throw, so an optional enrichment can never
 * block a valid interview turn (§17.2, §17.3).
 */
export interface PracticeContextReader {
  findForExperienceProfile(experienceProfileId: string): Promise<PracticeContextItem[]>;
}

/**
 * Shared marker prefix for the practice block. Declared on the conversation side
 * so the renderer and the derivation-side block-escape guard test the SAME
 * literal (§13.6, §14.5) without either module importing the other's internals.
 */
export const PRACTICE_CONTEXT_MARKER_PREFIX = "===== PRACTICE CONTEXT";
