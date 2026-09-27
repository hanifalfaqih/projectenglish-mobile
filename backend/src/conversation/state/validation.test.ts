import { describe, it, expect } from "vitest";
import { validateProposal, isLegalTransition, normalizeLabel } from "./validation.js";
import type { ConversationState } from "./schema.js";

const baseState: ConversationState = {
  phase: "experience",
  topics: [{ id: "t1", label: "mobile app", covered: false }],
  currentTopicId: null,
  questionCount: 0,
};

describe("validateProposal — Stage 1 (structural)", () => {
  it("accepts a valid proposal", () => {
    const result = validateProposal(
      { phase: "deep_dive", newTopics: [{ label: "backend" }] },
      baseState,
    );
    expect(result.valid).toBe(true);
    expect(result.proposal).not.toBeNull();
  });

  it("accepts an empty proposal (no fields)", () => {
    const result = validateProposal({}, baseState);
    expect(result.valid).toBe(true);
    expect(result.proposal).toEqual({});
  });

  it("rejects unknown fields (strict)", () => {
    const result = validateProposal(
      { phase: "experience", unknownField: true },
      baseState,
    );
    expect(result.valid).toBe(false);
    expect(result.proposal).toBeNull();
  });

  it("rejects invalid enum value for phase", () => {
    const result = validateProposal({ phase: "invalid_phase" }, baseState);
    expect(result.valid).toBe(false);
    expect(result.proposal).toBeNull();
  });

  it("rejects non-object input", () => {
    const result = validateProposal("not an object", baseState);
    expect(result.valid).toBe(false);
  });

  it("rejects null input", () => {
    const result = validateProposal(null, baseState);
    expect(result.valid).toBe(false);
  });

  it("rejects proposal with questionCount field", () => {
    const result = validateProposal({ questionCount: 5 }, baseState);
    expect(result.valid).toBe(false);
  });

  it("rejects newTopics with empty label", () => {
    const result = validateProposal(
      { newTopics: [{ label: "" }] },
      baseState,
    );
    expect(result.valid).toBe(false);
  });

  it("accepts currentTopicId as null", () => {
    const result = validateProposal({ currentTopicId: null }, baseState);
    expect(result.valid).toBe(true);
    expect(result.proposal?.currentTopicId).toBeNull();
  });

  it("rejects newTopics with non-string label", () => {
    const result = validateProposal(
      { newTopics: [{ label: 123 }] },
      baseState,
    );
    expect(result.valid).toBe(false);
  });
});

describe("validateProposal — Stage 2 is handled by merge", () => {
  it("returns valid=true for structurally valid proposal (per-op validation in merge)", () => {
    const result = validateProposal(
      { currentTopicId: "nonexistent" },
      baseState,
    );
    expect(result.valid).toBe(true);
  });
});

describe("isLegalTransition", () => {
  it("intro → intro is legal", () => {
    expect(isLegalTransition("intro", "intro")).toBe(true);
  });

  it("intro → experience is legal", () => {
    expect(isLegalTransition("intro", "experience")).toBe(true);
  });

  it("intro → deep_dive is illegal", () => {
    expect(isLegalTransition("intro", "deep_dive")).toBe(false);
  });

  it("intro → wrap_up is illegal", () => {
    expect(isLegalTransition("intro", "wrap_up")).toBe(false);
  });

  it("experience → deep_dive is legal", () => {
    expect(isLegalTransition("experience", "deep_dive")).toBe(true);
  });

  it("experience → wrap_up is legal", () => {
    expect(isLegalTransition("experience", "wrap_up")).toBe(true);
  });

  it("deep_dive → experience is legal", () => {
    expect(isLegalTransition("deep_dive", "experience")).toBe(true);
  });

  it("deep_dive → intro is illegal", () => {
    expect(isLegalTransition("deep_dive", "intro")).toBe(false);
  });

  it("wrap_up → wrap_up is legal", () => {
    expect(isLegalTransition("wrap_up", "wrap_up")).toBe(true);
  });

  it("wrap_up → experience is illegal (no exit)", () => {
    expect(isLegalTransition("wrap_up", "experience")).toBe(false);
  });

  it("wrap_up → intro is illegal", () => {
    expect(isLegalTransition("wrap_up", "intro")).toBe(false);
  });
});

describe("normalizeLabel", () => {
  it("trims whitespace", () => {
    expect(normalizeLabel("  hello  ")).toBe("hello");
  });

  it("collapses internal whitespace", () => {
    expect(normalizeLabel("hello   world")).toBe("hello world");
  });

  it("handles tabs and newlines", () => {
    expect(normalizeLabel("hello\t\nworld")).toBe("hello world");
  });
});
