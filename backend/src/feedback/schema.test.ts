import { describe, it, expect } from "vitest";
import {
  FeedbackOutput,
  ProposedAnswerFeedbackItem,
  parseFeedbackOutput,
  FEEDBACK_LIMITS,
} from "./schema.js";

describe("FeedbackOutput schema (qualitative-only)", () => {
  it("accepts a minimal valid output", () => {
    const parsed = FeedbackOutput.parse({
      overall: "You communicated your experience clearly overall.",
      answerItems: [],
    });
    expect(parsed.overall).toContain("clearly");
    expect(parsed.answerItems).toEqual([]);
  });

  it("accepts answer items with optional qualitative fields", () => {
    const parsed = FeedbackOutput.parse({
      overall: "Good.",
      answerItems: [
        {
          answerMessageId: "m1",
          questionMessageId: "q1",
          whatWorked: "Concrete example.",
          couldImprove: "Add the outcome.",
        },
      ],
    });
    expect(parsed.answerItems[0].tryNextTime).toBeUndefined();
    expect(parsed.answerItems[0].whatWorked).toBe("Concrete example.");
  });

  it("accepts optional professionalCommunication notes", () => {
    const parsed = FeedbackOutput.parse({
      overall: "Good.",
      answerItems: [],
      professionalCommunication: ["Showed clear ownership."],
    });
    expect(parsed.professionalCommunication).toEqual(["Showed clear ownership."]);
  });

  it("rejects unknown / numeric fields on the output (no scores)", () => {
    expect(() =>
      FeedbackOutput.parse({
        overall: "Good.",
        answerItems: [],
        score: 87,
      }),
    ).toThrow();
    expect(() =>
      FeedbackOutput.parse({
        overall: "Good.",
        answerItems: [],
        rating: 4,
      }),
    ).toThrow();
  });

  it("rejects numeric/unknown fields on an answer item", () => {
    expect(() =>
      ProposedAnswerFeedbackItem.parse({
        answerMessageId: "m1",
        score: 5,
      }),
    ).toThrow();
  });

  it("requires a non-empty answerMessageId", () => {
    expect(() =>
      ProposedAnswerFeedbackItem.parse({ answerMessageId: "" }),
    ).toThrow();
  });

  it("requires a non-empty overall", () => {
    expect(() =>
      FeedbackOutput.parse({ overall: "   ", answerItems: [] }),
    ).toThrow();
  });

  it("enforces the max answer-items bound", () => {
    const items = Array.from({ length: FEEDBACK_LIMITS.MAX_ANSWER_ITEMS + 1 }, () => ({
      answerMessageId: "m1",
    }));
    expect(() =>
      FeedbackOutput.parse({ overall: "Good.", answerItems: items }),
    ).toThrow();
  });
});

describe("parseFeedbackOutput", () => {
  it("returns ok for valid output", () => {
    const r = parseFeedbackOutput({ overall: "Good.", answerItems: [] });
    expect(r.ok).toBe(true);
    expect(r.output).not.toBeNull();
  });

  it("returns not-ok for malformed output", () => {
    const r = parseFeedbackOutput({ notOverall: true });
    expect(r.ok).toBe(false);
    expect(r.output).toBeNull();
  });
});
