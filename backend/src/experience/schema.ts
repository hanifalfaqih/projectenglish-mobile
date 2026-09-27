import { z } from "zod";

/**
 * Canonical Experience Profile domain (Actual M9, Contract v0.2 §3).
 *
 * Manual-input-first. Future Resume/CV extraction must map into this same
 * canonical shape rather than introducing a CV-specific model.
 *
 * The entire profile is used as interview grounding context. There is NO
 * selection/ranking/retrieval. This is deliberately NOT a full CV database:
 * no dates, no proficiency, no headline/summary/displayName.
 */

// Engineering validation boundaries (Contract v0.2 §8). Exact values are
// implementation-level defaults; the canonical shape is what is locked.
export const LIMITS = {
  MAX_ITEMS: 20,
  MIN_ITEMS: 1,
  TITLE_MAX: 200,
  ORGANIZATION_MAX: 200,
  ROLE_MAX: 200,
  DESCRIPTION_MAX: 2000,
  MAX_SKILLS: 30,
  SKILL_MAX: 50,
  // Overall serialized experience payload bound, enforced before prompt build.
  PAYLOAD_MAX_CHARS: 12000,
} as const;

/** Trim, then collapse internal whitespace runs to single spaces. */
function normalizeText(value: string): string {
  return value.trim().replace(/\s+/g, " ");
}

const trimmedNonEmpty = (max: number) =>
  z
    .string()
    .transform(normalizeText)
    .pipe(z.string().min(1).max(max));

const trimmedOptional = (max: number) =>
  z
    .string()
    .transform(normalizeText)
    .pipe(z.string().max(max))
    // Treat blank-after-trim optional fields as absent.
    .transform((v) => (v.length === 0 ? undefined : v))
    .nullable()
    .optional();

const SkillSchema = z
  .string()
  .transform(normalizeText)
  .pipe(z.string().min(1).max(LIMITS.SKILL_MAX));

/**
 * Case-insensitive de-duplication of skills, preserving first-seen order and
 * original casing. Applied after per-skill validation.
 */
function dedupeSkills(skills: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const skill of skills) {
    const key = skill.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(skill);
  }
  return result;
}

/** Request-shape for a single item on profile creation (no id/position). */
export const ExperienceItemInput = z
  .object({
    title: trimmedNonEmpty(LIMITS.TITLE_MAX),
    organization: trimmedOptional(LIMITS.ORGANIZATION_MAX),
    role: trimmedOptional(LIMITS.ROLE_MAX),
    description: trimmedNonEmpty(LIMITS.DESCRIPTION_MAX),
    skills: z
      .array(SkillSchema)
      .max(LIMITS.MAX_SKILLS)
      .transform(dedupeSkills)
      .optional()
      .default([]),
  })
  .strict();
export type ExperienceItemInput = z.infer<typeof ExperienceItemInput>;

/** Request-shape for POST /experience-profiles. */
export const CreateExperienceProfileInput = z
  .object({
    items: z
      .array(ExperienceItemInput)
      .min(LIMITS.MIN_ITEMS)
      .max(LIMITS.MAX_ITEMS),
  })
  .strict();
export type CreateExperienceProfileInput = z.infer<
  typeof CreateExperienceProfileInput
>;

/** Persisted/domain representation returned by the repository. */
export interface ExperienceItem {
  id: string;
  title: string;
  organization: string | null;
  role: string | null;
  description: string;
  skills: string[];
  position: number;
}

export interface ExperienceProfile {
  id: string;
  items: ExperienceItem[];
  createdAt: Date;
  updatedAt: Date;
}

/**
 * Compute the serialized payload size used for the overall payload bound.
 * Counts only candidate-provided content (title/org/role/description/skills).
 */
export function serializedPayloadSize(
  input: CreateExperienceProfileInput,
): number {
  let total = 0;
  for (const item of input.items) {
    total += item.title.length;
    total += item.organization?.length ?? 0;
    total += item.role?.length ?? 0;
    total += item.description.length;
    for (const skill of item.skills) total += skill.length;
  }
  return total;
}

export interface PayloadValidationResult {
  ok: boolean;
  size: number;
  max: number;
}

export function checkPayloadBound(
  input: CreateExperienceProfileInput,
): PayloadValidationResult {
  const size = serializedPayloadSize(input);
  return { ok: size <= LIMITS.PAYLOAD_MAX_CHARS, size, max: LIMITS.PAYLOAD_MAX_CHARS };
}
