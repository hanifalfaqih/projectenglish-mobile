import { describe, it, expect } from "vitest";
import {
  CreateExperienceProfileInput,
  ExperienceItemInput,
  checkPayloadBound,
  LIMITS,
} from "./schema.js";

describe("ExperienceItemInput validation", () => {
  it("accepts a valid minimal item (title + description only)", () => {
    const parsed = ExperienceItemInput.parse({
      title: "Backend Intern",
      description: "Built and maintained REST APIs for the billing service.",
    });
    expect(parsed.title).toBe("Backend Intern");
    expect(parsed.description).toContain("REST APIs");
    // Optional fields absent; skills default to empty array.
    expect(parsed.organization).toBeUndefined();
    expect(parsed.role).toBeUndefined();
    expect(parsed.skills).toEqual([]);
  });

  it("accepts optional organization and role", () => {
    const parsed = ExperienceItemInput.parse({
      title: "Backend Intern",
      organization: "PT Example",
      role: "Backend Developer",
      description: "Worked on APIs.",
    });
    expect(parsed.organization).toBe("PT Example");
    expect(parsed.role).toBe("Backend Developer");
  });

  it("accepts and de-duplicates skills case-insensitively, preserving first-seen", () => {
    const parsed = ExperienceItemInput.parse({
      title: "Project",
      description: "A project.",
      skills: ["TypeScript", "typescript", "Node", "node", "TypeScript"],
    });
    expect(parsed.skills).toEqual(["TypeScript", "Node"]);
  });

  it("normalizes internal whitespace and trims", () => {
    const parsed = ExperienceItemInput.parse({
      title: "  Backend    Intern  ",
      description: "  did   things  ",
    });
    expect(parsed.title).toBe("Backend Intern");
    expect(parsed.description).toBe("did things");
  });

  it("rejects empty title after trim", () => {
    expect(() =>
      ExperienceItemInput.parse({ title: "   ", description: "ok" }),
    ).toThrow();
  });

  it("rejects empty description after trim", () => {
    expect(() =>
      ExperienceItemInput.parse({ title: "ok", description: "   " }),
    ).toThrow();
  });

  it("rejects unknown fields (strict)", () => {
    expect(() =>
      ExperienceItemInput.parse({
        title: "ok",
        description: "ok",
        proficiency: "expert",
      }),
    ).toThrow();
  });

  it("rejects title over the length limit", () => {
    expect(() =>
      ExperienceItemInput.parse({
        title: "a".repeat(LIMITS.TITLE_MAX + 1),
        description: "ok",
      }),
    ).toThrow();
  });

  it("rejects description over the length limit", () => {
    expect(() =>
      ExperienceItemInput.parse({
        title: "ok",
        description: "a".repeat(LIMITS.DESCRIPTION_MAX + 1),
      }),
    ).toThrow();
  });

  it("rejects more than the max number of skills", () => {
    expect(() =>
      ExperienceItemInput.parse({
        title: "ok",
        description: "ok",
        skills: Array.from({ length: LIMITS.MAX_SKILLS + 1 }, (_, i) => `s${i}`),
      }),
    ).toThrow();
  });

  it("rejects a skill over the per-skill length limit", () => {
    expect(() =>
      ExperienceItemInput.parse({
        title: "ok",
        description: "ok",
        skills: ["a".repeat(LIMITS.SKILL_MAX + 1)],
      }),
    ).toThrow();
  });
});

describe("CreateExperienceProfileInput validation", () => {
  it("accepts a profile with a single item", () => {
    const parsed = CreateExperienceProfileInput.parse({
      items: [{ title: "t", description: "d" }],
    });
    expect(parsed.items).toHaveLength(1);
  });

  it("rejects a profile with zero items", () => {
    expect(() => CreateExperienceProfileInput.parse({ items: [] })).toThrow();
  });

  it("rejects more than the max number of items", () => {
    const items = Array.from({ length: LIMITS.MAX_ITEMS + 1 }, () => ({
      title: "t",
      description: "d",
    }));
    expect(() => CreateExperienceProfileInput.parse({ items })).toThrow();
  });

  it("rejects unknown top-level fields (strict)", () => {
    expect(() =>
      CreateExperienceProfileInput.parse({
        items: [{ title: "t", description: "d" }],
        displayName: "Jane",
      }),
    ).toThrow();
  });
});

describe("checkPayloadBound", () => {
  it("passes for a normal profile", () => {
    const input = CreateExperienceProfileInput.parse({
      items: [{ title: "t", description: "a real description" }],
    });
    const result = checkPayloadBound(input);
    expect(result.ok).toBe(true);
    expect(result.size).toBeGreaterThan(0);
    expect(result.max).toBe(LIMITS.PAYLOAD_MAX_CHARS);
  });

  it("fails when total serialized content exceeds the payload bound", () => {
    // Each item description is near its own max; enough items to exceed the
    // overall payload bound while staying within per-item + item-count limits.
    const bigDescription = "a".repeat(LIMITS.DESCRIPTION_MAX);
    const items = Array.from({ length: LIMITS.MAX_ITEMS }, () => ({
      title: "t",
      description: bigDescription,
    }));
    const input = CreateExperienceProfileInput.parse({ items });
    const result = checkPayloadBound(input);
    expect(result.ok).toBe(false);
    expect(result.size).toBeGreaterThan(LIMITS.PAYLOAD_MAX_CHARS);
  });
});
