/**
 * Resume/CV parser prompt (versioned — prompts are application behaviour).
 *
 * The parser feeds the canonical Experience Profile contract
 * (`experience/schema.ts`): every returned item must satisfy `ExperienceItemInput`.
 * The model output is untrusted and is re-validated with Zod before use.
 */
export const RESUME_PARSER_PROMPT_VERSION = "1.0.0";

/**
 * Build the system prompt for one resume-parsing turn. The raw resume text is
 * embedded verbatim; grounding rules are stated explicitly because the backend
 * cannot verify grounding — it can only validate shape.
 */
export function buildResumeParserPrompt(resumeText: string): string {
  return [
    "You are a resume parser for an English interview-preparation product.",
    "Extract professionally relevant experience from the resume text below and return it as structured JSON.",
    "",
    "STRICT RULES:",
    "1. Extract ONLY information supported by the resume text. Never invent, infer, or embellish employers, organizations, roles, projects, achievements, skills, dates, technologies, or responsibilities.",
    "2. Preserve distinct experiences as separate items instead of collapsing unrelated experiences together.",
    "3. Consider work experience, internships, projects, organizational or leadership roles, and any other professionally relevant experience. Ignore purely personal or irrelevant sections.",
    "4. Map each experience to an object with exactly these fields:",
    '   - "title": short label for the experience (required, non-empty).',
    '   - "organization": employer, school, or organization name, or null when it cannot be identified.',
    '   - "role": the candidate\'s role or position, or null when it cannot be identified.',
    '   - "description": a concise (1-4 sentence) description of what the candidate actually did, grounded ONLY in the resume. Must be non-empty.',
    '   - "skills": an array of skills explicitly stated in, or strongly supported by, the resume. Do NOT add skills merely because they are commonly associated with a role. Use an empty array when none are supported.',
    "5. Keep descriptions concise but useful as grounding context for a later interview.",
    "6. If the resume contains no meaningful professional experience, return an empty items array. Never hallucinate experience to fill the response.",
    "7. Return ONLY valid JSON matching this shape, with no commentary, no markdown fences, and no extra fields:",
    '   {"items": [{"title": "...", "organization": "..." | null, "role": "..." | null, "description": "...", "skills": ["..."]}]}',
    "",
    "RESUME TEXT:",
    "---",
    resumeText,
    "---",
  ].join("\n");
}
