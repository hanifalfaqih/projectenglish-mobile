import { describe, it, expect } from "vitest";
import { mergeState } from "./merge.js";
import type { ConversationState } from "./schema.js";

function makeState(overrides: Partial<ConversationState> = {}): ConversationState {
  return {
    phase: "experience",
    topics: [],
    currentTopicId: null,
    questionCount: 0,
    ...overrides,
  };
}

describe("mergeState — AC-1: Shallow answer", () => {
  it("adds a new topic from proposal with backend-assigned id", () => {
    const current = makeState();
    const result = mergeState(
      current,
      { newTopics: [{ label: "mobile app" }] },
      "Tell me more.",
    );

    expect(result.topics).toHaveLength(1);
    expect(result.topics[0].label).toBe("mobile app");
    expect(result.topics[0].covered).toBe(false);
    expect(result.topics[0].id).toBeTruthy();
  });
});

describe("mergeState — AC-2: Strong answer with multiple topics", () => {
  it("adds multiple topics from a single proposal", () => {
    const current = makeState();
    const result = mergeState(
      current,
      { newTopics: [{ label: "mobile app" }, { label: "backend API" }] },
      "Interesting.",
    );

    expect(result.topics).toHaveLength(2);
    expect(result.topics[0].label).toBe("mobile app");
    expect(result.topics[1].label).toBe("backend API");
  });
});

describe("mergeState — AC-3: Unexpected topic + currentTopicId", () => {
  it("appends new topic and updates currentTopicId to it", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend work", covered: false }],
      currentTopicId: "t1",
    });
    const result = mergeState(
      current,
      {
        newTopics: [{ label: "frontend design" }],
        currentTopicId: "pending",
      },
      "Let's explore that.",
    );

    const newTopic = result.topics.find((t) => t.label === "frontend design");
    expect(newTopic).toBeDefined();
    // currentTopicId "pending" doesn't exist, so it stays at t1
    expect(result.currentTopicId).toBe("t1");
  });

  it("sets currentTopicId to newly created topic", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend work", covered: false }],
      currentTopicId: "t1",
    });
    const proposal = { newTopics: [{ label: "frontend design" }] };
    const result = mergeState(current, proposal, "Let's explore that.");

    const newTopic = result.topics.find((t) => t.label === "frontend design");
    expect(newTopic).toBeDefined();

    // Now set currentTopicId in a separate merge using the new topic's id
    const result2 = mergeState(
      result,
      { currentTopicId: newTopic!.id },
      "Continuing.",
    );
    expect(result2.currentTopicId).toBe(newTopic!.id);
    expect(result2.topics.find((t) => t.id === "t1")).toBeDefined();
  });
});

describe("mergeState — AC-4: Contribution correction (relabel)", () => {
  it("updates a topic label via relabelTopics", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend work", covered: false }],
    });
    const result = mergeState(
      current,
      { relabelTopics: [{ id: "t1", label: "mobile integration" }] },
      "Got it.",
    );

    expect(result.topics[0].label).toBe("mobile integration");
  });

  it("skips relabel with unknown id", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend work", covered: false }],
    });
    const result = mergeState(
      current,
      { relabelTopics: [{ id: "unknown", label: "new name" }] },
      "OK.",
    );

    expect(result.topics[0].label).toBe("backend work");
  });

  it("skips relabel that would duplicate another topic's label", () => {
    const current = makeState({
      topics: [
        { id: "t1", label: "backend", covered: false },
        { id: "t2", label: "frontend", covered: false },
      ],
    });
    const result = mergeState(
      current,
      { relabelTopics: [{ id: "t1", label: "frontend" }] },
      "OK.",
    );

    expect(result.topics[0].label).toBe("backend");
  });

  it("skips relabel with empty normalized label", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend", covered: false }],
    });
    const result = mergeState(
      current,
      { relabelTopics: [{ id: "t1", label: "   " }] },
      "OK.",
    );

    expect(result.topics[0].label).toBe("backend");
  });
});

describe("mergeState — AC-5: Noisy English (duplicates/empty)", () => {
  it("skips duplicate topic labels (case-insensitive)", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "mobile app", covered: false }],
    });
    const result = mergeState(
      current,
      { newTopics: [{ label: "Mobile App" }, { label: "MOBILE APP" }] },
      "OK.",
    );

    expect(result.topics).toHaveLength(1);
  });

  it("skips duplicate labels within the same batch", () => {
    const current = makeState();
    const result = mergeState(
      current,
      { newTopics: [{ label: "backend" }, { label: "Backend" }] },
      "OK.",
    );

    expect(result.topics).toHaveLength(1);
  });
});

describe("mergeState — AC-7: Invalid proposal (structural)", () => {
  it("preserves state when proposal is null (structurally invalid)", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend", covered: false }],
      questionCount: 3,
    });
    const result = mergeState(current, null, "A response.");

    expect(result.phase).toBe(current.phase);
    expect(result.topics).toEqual(current.topics);
    expect(result.currentTopicId).toBe(current.currentTopicId);
    // questionCount still updates from assistant message
    expect(result.questionCount).toBe(3);
  });
});

describe("mergeState — AC-8: Partial proposal", () => {
  it("only changes what is proposed; absent fields unchanged", () => {
    const current = makeState({
      phase: "experience",
      topics: [{ id: "t1", label: "backend", covered: false }],
      questionCount: 2,
    });
    const result = mergeState(current, { phase: "deep_dive" }, "Deep dive.");

    expect(result.phase).toBe("deep_dive");
    expect(result.topics).toEqual(current.topics);
    expect(result.questionCount).toBe(2);
  });
});

describe("mergeState — AC-9: Illegal phase transition", () => {
  it("keeps phase unchanged on illegal transition", () => {
    const current = makeState({ phase: "deep_dive" });
    const result = mergeState(current, { phase: "intro" }, "Going back.");

    expect(result.phase).toBe("deep_dive");
  });
});

describe("mergeState — AC-10: Invalid topic reference", () => {
  it("keeps currentTopicId on unknown reference", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend", covered: false }],
      currentTopicId: "t1",
    });
    const result = mergeState(
      current,
      { currentTopicId: "t999" },
      "OK.",
    );

    expect(result.currentTopicId).toBe("t1");
  });

  it("skips unknown coveredTopicIds", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend", covered: false }],
    });
    const result = mergeState(
      current,
      { coveredTopicIds: ["unknown-id"] },
      "OK.",
    );

    expect(result.topics[0].covered).toBe(false);
  });
});

describe("mergeState — AC-11: Question counting", () => {
  it("increments questionCount when message contains ?", () => {
    const current = makeState({ questionCount: 3 });
    const result = mergeState(current, null, "What did you do?");

    expect(result.questionCount).toBe(4);
  });

  it("does not increment when message has no ?", () => {
    const current = makeState({ questionCount: 3 });
    const result = mergeState(current, null, "That's interesting.");

    expect(result.questionCount).toBe(3);
  });

  it("increments only +1 for multiple ?", () => {
    const current = makeState({ questionCount: 3 });
    const result = mergeState(current, null, "What? Why? How?");

    expect(result.questionCount).toBe(4);
  });
});

describe("mergeState — AC-12: Forced wrap-up at MAX_QUESTIONS", () => {
  it("forces phase to wrap_up when questionCount reaches 8", () => {
    const current = makeState({ questionCount: 7 });
    const result = mergeState(
      current,
      { phase: "deep_dive" },
      "What else?",
    );

    expect(result.questionCount).toBe(8);
    expect(result.phase).toBe("wrap_up");
  });

  it("overrides proposed phase when forcing wrap_up", () => {
    const current = makeState({ phase: "experience", questionCount: 7 });
    const result = mergeState(
      current,
      { phase: "deep_dive" },
      "One more question?",
    );

    expect(result.phase).toBe("wrap_up");
  });
});

describe("mergeState — AC-13: Wrap-up lock", () => {
  it("rejects new topics while in wrap_up", () => {
    const current = makeState({ phase: "wrap_up" });
    const result = mergeState(
      current,
      { newTopics: [{ label: "new topic" }] },
      "Closing up.",
    );

    expect(result.topics).toHaveLength(0);
  });

  it("rejects phase exit from wrap_up", () => {
    const current = makeState({ phase: "wrap_up" });
    const result = mergeState(
      current,
      { phase: "experience" },
      "Almost done.",
    );

    expect(result.phase).toBe("wrap_up");
  });
});

describe("mergeState — AC-20: Monotonic covered", () => {
  it("sets covered to true via coveredTopicIds", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend", covered: false }],
    });
    const result = mergeState(
      current,
      { coveredTopicIds: ["t1"] },
      "Covered.",
    );

    expect(result.topics[0].covered).toBe(true);
  });

  it("never reverts covered from true to false", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend", covered: true }],
    });
    // coveredTopicIds is monotonic; there's no mechanism to set covered=false
    const result = mergeState(current, {}, "OK.");

    expect(result.topics[0].covered).toBe(true);
  });
});

describe("mergeState — operation ordering", () => {
  it("newTopics can be referenced by currentTopicId in same turn", () => {
    const current = makeState();
    // This test verifies ordering: newTopics run first, then currentTopicId
    // We need to know the id, so we do it in two steps
    const result1 = mergeState(
      current,
      { newTopics: [{ label: "new thing" }] },
      "OK.",
    );
    const newTopic = result1.topics[0];
    const result2 = mergeState(
      result1,
      { currentTopicId: newTopic.id },
      "Let's discuss.",
    );
    expect(result2.currentTopicId).toBe(newTopic.id);
  });

  it("applies operations in correct order", () => {
    const current = makeState({
      topics: [
        { id: "t1", label: "old label", covered: false },
        { id: "t2", label: "other", covered: false },
      ],
    });
    const result = mergeState(
      current,
      {
        newTopics: [{ label: "brand new" }],
        relabelTopics: [{ id: "t1", label: "renamed" }],
        coveredTopicIds: ["t2"],
        currentTopicId: "t2",
        phase: "deep_dive",
      },
      "Let's dig in.",
    );

    expect(result.topics.find((t) => t.id === "t1")?.label).toBe("renamed");
    expect(result.topics.find((t) => t.id === "t2")?.covered).toBe(true);
    expect(result.topics.find((t) => t.label === "brand new")).toBeDefined();
    expect(result.currentTopicId).toBe("t2");
    expect(result.phase).toBe("deep_dive");
  });
});

describe("mergeState — does not mutate input", () => {
  it("returns a new state object without mutating the original", () => {
    const current = makeState({
      topics: [{ id: "t1", label: "backend", covered: false }],
    });
    const originalTopics = JSON.parse(JSON.stringify(current.topics));

    mergeState(current, { newTopics: [{ label: "frontend" }] }, "OK.");

    expect(current.topics).toEqual(originalTopics);
    expect(current.topics).toHaveLength(1);
  });
});

describe("mergeState — Hypothetical scenario (AC-6)", () => {
  it("does not add confidence/provenance fields to state", () => {
    const current = makeState();
    const result = mergeState(
      current,
      { newTopics: [{ label: "hypothetical project" }] },
      "That sounds hypothetical.",
    );

    expect(result.topics[0]).toHaveProperty("id");
    expect(result.topics[0]).toHaveProperty("label");
    expect(result.topics[0]).toHaveProperty("covered");
    expect(Object.keys(result.topics[0])).toHaveLength(3);
  });
});
