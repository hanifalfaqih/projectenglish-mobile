import { describe, it, expect } from "vitest";
import {
  SubmitRetryRequest,
  parseRetryFeedbackOutput,
  RETRY_LIMITS,
} from "./schema.js";

describe("SubmitRetryRequest", () => {
  const valid = {
    answerMessageId: "u1",
    questionMessageId: "a1",
    retryAnswer: "Improved answer.",
    retryClientKey: "key-1",
  };

  it("accepts a well-formed request", () => {
    expect(SubmitRetryRequest.safeParse(valid).success).toBe(true);
  });

  it("rejects missing fields", () => {
    expect(
      SubmitRetryRequest.safeParse({ ...valid, answerMessageId: "" }).success,
    ).toBe(false);
    expect(
      SubmitRetryRequest.safeParse({ ...valid, retryClientKey: "" }).success,
    ).toBe(false);
  });

  it("rejects blank or oversized retry answers", () => {
    expect(
      SubmitRetryRequest.safeParse({ ...valid, retryAnswer: "   " }).success,
    ).toBe(false);
    expect(
      SubmitRetryRequest.safeParse({
        ...valid,
        retryAnswer: "x".repeat(RETRY_LIMITS.RETRY_ANSWER_MAX + 1),
      }).success,
    ).toBe(false);
  });

  it("rejects oversized client keys and unknown fields", () => {
    expect(
      SubmitRetryRequest.safeParse({
        ...valid,
        retryClientKey: "k".repeat(201),
      }).success,
    ).toBe(false);
    expect(
      SubmitRetryRequest.safeParse({ ...valid, extra: "nope" }).success,
    ).toBe(false);
  });
});

describe("parseRetryFeedbackOutput", () => {
  it("accepts minimal valid output", () => {
    const parsed = parseRetryFeedbackOutput({ overall: "Better." });
    expect(parsed.ok).toBe(true);
    expect(parsed.feedback?.overall).toBe("Better.");
    expect(parsed.feedback?.professionalCommunication).toBeNull();
  });

  it("rejects numeric fields and unknown fields", () => {
    expect(parseRetryFeedbackOutput({ overall: "x", score: 9 }).ok).toBe(
      false,
    );
    expect(parseRetryFeedbackOutput({ overall: "x", extra: true }).ok).toBe(
      false,
    );
  });

  it("rejects missing overall", () => {
    expect(parseRetryFeedbackOutput({ whatWorked: "x" }).ok).toBe(false);
  });
});
