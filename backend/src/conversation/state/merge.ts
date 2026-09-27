import { randomUUID } from "node:crypto";
import type { ConversationState, Phase, StateProposal, Topic } from "./schema.js";
import { MAX_QUESTIONS } from "./schema.js";
import { isLegalTransition, labelsEqual, normalizeLabel } from "./validation.js";

export function mergeState(
  current: ConversationState,
  proposal: StateProposal | null,
  assistantMessage: string,
): ConversationState {
  const next: ConversationState = {
    phase: current.phase,
    topics: current.topics.map((t) => ({ ...t })),
    currentTopicId: current.currentTopicId,
    questionCount: current.questionCount,
  };

  if (proposal) {
    applyNewTopics(next, proposal);
    applyRelabelTopics(next, proposal);
    applyCoveredTopicIds(next, proposal);
    applyCurrentTopicId(next, proposal);
    applyPhase(next, proposal);
  }

  const hasQuestion = assistantMessage.includes("?");
  next.questionCount = current.questionCount + (hasQuestion ? 1 : 0);

  if (next.questionCount >= MAX_QUESTIONS) {
    next.phase = "wrap_up";
  }

  return next;
}

function applyNewTopics(state: ConversationState, proposal: StateProposal): void {
  if (!proposal.newTopics) return;

  if (state.phase === "wrap_up") return;

  const existingLabels = state.topics.map((t) => normalizeLabel(t.label));
  const batchLabels: string[] = [];

  for (const item of proposal.newTopics) {
    const normalized = normalizeLabel(item.label);
    if (!normalized) continue;

    if (existingLabels.some((l) => labelsEqual(l, normalized))) continue;
    if (batchLabels.some((l) => labelsEqual(l, normalized))) continue;

    const topic: Topic = {
      id: randomUUID(),
      label: normalized,
      covered: false,
    };
    state.topics.push(topic);
    existingLabels.push(normalized);
    batchLabels.push(normalized);
  }
}

function applyRelabelTopics(
  state: ConversationState,
  proposal: StateProposal,
): void {
  if (!proposal.relabelTopics) return;

  for (const relabel of proposal.relabelTopics) {
    const topic = state.topics.find((t) => t.id === relabel.id);
    if (!topic) continue;

    const newLabel = normalizeLabel(relabel.label);
    if (!newLabel) continue;

    const duplicate = state.topics.some(
      (t) => t.id !== relabel.id && labelsEqual(normalizeLabel(t.label), newLabel),
    );
    if (duplicate) continue;

    topic.label = newLabel;
  }
}

function applyCoveredTopicIds(
  state: ConversationState,
  proposal: StateProposal,
): void {
  if (!proposal.coveredTopicIds) return;

  for (const id of proposal.coveredTopicIds) {
    const topic = state.topics.find((t) => t.id === id);
    if (topic) {
      topic.covered = true;
    }
  }
}

function applyCurrentTopicId(
  state: ConversationState,
  proposal: StateProposal,
): void {
  if (proposal.currentTopicId === undefined) return;

  if (proposal.currentTopicId === null) {
    state.currentTopicId = null;
    return;
  }

  const exists = state.topics.some((t) => t.id === proposal.currentTopicId);
  if (exists) {
    state.currentTopicId = proposal.currentTopicId;
  }
}

function applyPhase(state: ConversationState, proposal: StateProposal): void {
  if (proposal.phase === undefined) return;

  if (state.phase === "wrap_up") return;

  if (isLegalTransition(state.phase, proposal.phase)) {
    state.phase = proposal.phase;
  }
}
