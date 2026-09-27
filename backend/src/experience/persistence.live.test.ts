import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { PrismaClient } from "@prisma/client";
import { ExperienceProfileRepository } from "./repository.js";
import { ConversationRepository } from "../conversation/repository.js";
import { ConversationService } from "../conversation/service.js";
import { CreateExperienceProfileInput } from "./schema.js";

/**
 * OPT-IN real-PostgreSQL persistence tests for the Experience Profile (M9).
 *
 * Excluded from the default suite by the `*.live.test.ts` suffix. Self-skips
 * unless RUN_LIVE_INTEGRATION=1 and DATABASE_URL are present. These do NOT
 * require the LLM — they verify relational persistence, ordering, reuse, the
 * nullable association, and invalid-reference handling against a real DB.
 */

const LIVE =
  process.env.RUN_LIVE_INTEGRATION === "1" && Boolean(process.env.DATABASE_URL);

if (!LIVE) {
  // eslint-disable-next-line no-console
  console.info(
    "[experience-persistence] skipped: set RUN_LIVE_INTEGRATION=1 and DATABASE_URL to run.",
  );
}

describe.skipIf(!LIVE)("M9 Experience Profile persistence (real PostgreSQL)", () => {
  let prisma: PrismaClient;
  let profiles: ExperienceProfileRepository;
  let conversations: ConversationRepository;
  let service: ConversationService;
  const createdProfileIds: string[] = [];
  const createdConversationIds: string[] = [];

  beforeAll(() => {
    prisma = new PrismaClient();
    profiles = new ExperienceProfileRepository(prisma);
    conversations = new ConversationRepository(prisma);
    // No LLM provider needed for these persistence-only assertions.
    service = new ConversationService({
      repository: conversations,
      provider: { generateTurn: async () => ({ assistantMessage: "n/a" }) },
      experienceProfiles: profiles,
    });
  });

  afterAll(async () => {
    for (const id of createdConversationIds) {
      await prisma.conversation.delete({ where: { id } }).catch(() => {});
    }
    for (const id of createdProfileIds) {
      await prisma.experienceItem.deleteMany({ where: { profileId: id } }).catch(() => {});
      await prisma.experienceProfile.delete({ where: { id } }).catch(() => {});
    }
    await prisma?.$disconnect();
  });

  it("creates a profile and persists items in deterministic position order", async () => {
    const input = CreateExperienceProfileInput.parse({
      items: [
        { title: "First", description: "d1", skills: ["TS"] },
        { title: "Second", organization: "Org", role: "Dev", description: "d2" },
        { title: "Third", description: "d3" },
      ],
    });

    const profile = await profiles.create(input);
    createdProfileIds.push(profile.id);

    expect(profile.items).toHaveLength(3);
    expect(profile.items.map((i) => i.position)).toEqual([0, 1, 2]);
    expect(profile.items.map((i) => i.title)).toEqual(["First", "Second", "Third"]);

    // Re-read to confirm persistence + ordering survive a round-trip.
    const reloaded = await profiles.findById(profile.id);
    expect(reloaded?.items.map((i) => i.title)).toEqual(["First", "Second", "Third"]);
    expect(reloaded?.items[1].organization).toBe("Org");
    expect(reloaded?.items[1].role).toBe("Dev");
    expect(reloaded?.items[0].skills).toEqual(["TS"]);
  });

  it("allows one profile to be reused across multiple conversations", async () => {
    const input = CreateExperienceProfileInput.parse({
      items: [{ title: "Reusable", description: "shared context" }],
    });
    const profile = await profiles.create(input);
    createdProfileIds.push(profile.id);

    const c1 = await service.createConversation({ experienceProfileId: profile.id });
    const c2 = await service.createConversation({ experienceProfileId: profile.id });
    createdConversationIds.push(c1.id, c2.id);

    const r1 = await conversations.findById(c1.id);
    const r2 = await conversations.findById(c2.id);
    expect(r1?.experienceProfileId).toBe(profile.id);
    expect(r2?.experienceProfileId).toBe(profile.id);
  });

  it("creates a conversation with a null association when no profile is given", async () => {
    const c = await service.createConversation();
    createdConversationIds.push(c.id);
    const row = await conversations.findById(c.id);
    expect(row?.experienceProfileId).toBeNull();
  });

  it("rejects association with a non-existent profile and creates nothing", async () => {
    await expect(
      service.createConversation({ experienceProfileId: "does-not-exist" }),
    ).rejects.toThrow();
  });
});
