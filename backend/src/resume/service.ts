import { z } from "zod";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import {
  ExperienceItemInput,
  LIMITS,
} from "../experience/schema.js";
import { extractPdfText, PdfExtractionError } from "./pdf.js";
import type { ResumeParserProvider } from "./provider.js";

/** Client error: the upload itself is unusable (type, size, shape, content). */
export class InvalidResumeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "InvalidResumeError";
  }
}

/** Client error: the upload exceeds the configured byte limit. */
export class ResumeTooLargeError extends Error {
  constructor(
    message: string,
    public readonly size: number,
    public readonly max: number,
  ) {
    super(message);
    this.name = "ResumeTooLargeError";
  }
}

/** Provider-side error: the model returned malformed or schema-invalid output. */
export class ResumeParseError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "ResumeParseError";
  }
}

export const PDF_MIME_TYPE = "application/pdf";

export interface ResumeLimits {
  maxFileBytes: number;
  maxTextChars: number;
}

export const DEFAULT_RESUME_LIMITS: ResumeLimits = {
  maxFileBytes:
    Number(process.env.RESUME_MAX_BYTES) || 5 * 1024 * 1024, // 5 MB
  maxTextChars: Number(process.env.RESUME_MAX_TEXT_CHARS) || 100_000,
};

/**
 * Parser response contract. Reuses the canonical `ExperienceItemInput` shape so
 * the result maps 1:1 into `POST /experience-profiles` items. Unlike profile
 * creation, an empty `items` array is valid here (no meaningful experience).
 */
export const ResumeParseOutputSchema = z
  .object({
    items: z.array(ExperienceItemInput).max(LIMITS.MAX_ITEMS),
  })
  .strict();
export type ResumeParseOutput = z.infer<typeof ResumeParseOutputSchema>;

export interface ParseResumeInput {
  mimetype: string;
  buffer: Buffer;
}

export interface ResumeParserServiceDependencies {
  provider: ResumeParserProvider;
  limits?: ResumeLimits;
}

/**
 * Orchestrates PDF → text → Qwen structured parsing.
 *
 * Stateless and persistence-free: it only parses and returns the structured
 * result. Callers decide whether to store it (e.g. via POST
 * /experience-profiles after user review). Never logs resume contents.
 */
export class ResumeParserService {
  private readonly limits: ResumeLimits;

  constructor(private readonly deps: ResumeParserServiceDependencies) {
    this.limits = deps.limits ?? DEFAULT_RESUME_LIMITS;
  }

  async parseResume(input: ParseResumeInput): Promise<ResumeParseOutput> {
    if (input.mimetype !== PDF_MIME_TYPE) {
      throw new InvalidResumeError(
        `Unsupported file type: expected ${PDF_MIME_TYPE}`,
      );
    }
    if (input.buffer.length > this.limits.maxFileBytes) {
      throw new ResumeTooLargeError(
        `Resume file too large: ${input.buffer.length} bytes exceeds ${this.limits.maxFileBytes} bytes`,
        input.buffer.length,
        this.limits.maxFileBytes,
      );
    }
    if (input.buffer.length === 0) {
      throw new InvalidResumeError("Resume file is empty");
    }

    let text: string;
    try {
      text = await extractPdfText(input.buffer);
    } catch (err) {
      if (err instanceof PdfExtractionError) {
        throw new InvalidResumeError(err.message);
      }
      throw err;
    }
    if (text.length === 0) {
      throw new InvalidResumeError(
        "PDF contains no extractable text (scanned documents are not supported)",
      );
    }
    if (text.length > this.limits.maxTextChars) {
      throw new ResumeTooLargeError(
        `Resume text too long: ${text.length} characters exceeds ${this.limits.maxTextChars} characters`,
        text.length,
        this.limits.maxTextChars,
      );
    }

    let raw: unknown;
    try {
      raw = await this.deps.provider.parseResumeText(text);
    } catch (err) {
      if (err instanceof ProviderTimeoutError) throw err;
      if (err instanceof ProviderError) throw err;
      throw new ProviderError("Resume parser provider failed", err);
    }

    // Structural validation of untrusted LLM output against the canonical
    // experience-item shape (normalizes whitespace, drops blank optionals,
    // de-duplicates skills, rejects unknown fields via strict schemas).
    const parsed = ResumeParseOutputSchema.safeParse(raw);
    if (!parsed.success) {
      throw new ResumeParseError(
        "Model returned experience data that does not match the expected schema",
        parsed.error.flatten(),
      );
    }
    return parsed.data;
  }
}
