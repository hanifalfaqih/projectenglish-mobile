import { describe, it, expect, vi } from "vitest";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import {
  InvalidResumeError,
  ResumeParseError,
  ResumeParserService,
  ResumeTooLargeError,
} from "./service.js";
import type { ResumeParserProvider } from "./provider.js";
import { buildMinimalPdf } from "./pdf-fixture.js";

const PDF_MIME = "application/pdf";

function providerReturning(raw: unknown): ResumeParserProvider {
  return { parseResumeText: vi.fn().mockResolvedValue(raw) };
}

function serviceWith(
  raw: unknown,
  limits = { maxFileBytes: 5 * 1024 * 1024, maxTextChars: 100_000 },
): { service: ResumeParserService; provider: ResumeParserProvider } {
  const provider = providerReturning(raw);
  return {
    service: new ResumeParserService({ provider, limits }),
    provider,
  };
}

const resumePdf = () =>
  buildMinimalPdf([
    "Jane Doe",
    "Backend Intern at PT Example",
    "Built REST APIs with Node.js. Skills: TypeScript, SQL.",
  ]);

describe("ResumeParserService — valid structured output", () => {
  it("returns a single valid experience item", async () => {
    const { service } = serviceWith({
      items: [
        {
          title: "Backend Intern",
          organization: "PT Example",
          role: "Backend Developer",
          description: "Built REST APIs with Node.js.",
          skills: ["TypeScript", "SQL"],
        },
      ],
    });
    const result = await service.parseResume({
      mimetype: PDF_MIME,
      buffer: resumePdf(),
    });
    expect(result.items).toHaveLength(1);
    expect(result.items[0]).toMatchObject({
      title: "Backend Intern",
      organization: "PT Example",
      role: "Backend Developer",
      skills: ["TypeScript", "SQL"],
    });
  });

  it("preserves multiple distinct experiences", async () => {
    const { service } = serviceWith({
      items: [
        {
          title: "Backend Intern",
          organization: "PT Example",
          role: "Intern",
          description: "Built REST APIs.",
          skills: ["Node.js"],
        },
        {
          title: "Campus App Project",
          organization: null,
          role: null,
          description: "Led a team of three building a campus app.",
          skills: ["React"],
        },
      ],
    });
    const result = await service.parseResume({
      mimetype: PDF_MIME,
      buffer: resumePdf(),
    });
    expect(result.items).toHaveLength(2);
    expect(result.items[0].title).toBe("Backend Intern");
    expect(result.items[1].title).toBe("Campus App Project");
  });

  it("accepts a missing organization", async () => {
    const { service } = serviceWith({
      items: [
        {
          title: "Open Source Contribution",
          organization: null,
          role: "Contributor",
          description: "Fixed bugs in a parser library.",
          skills: [],
        },
      ],
    });
    const result = await service.parseResume({
      mimetype: PDF_MIME,
      buffer: resumePdf(),
    });
    expect(result.items[0].organization).toBeNull();
  });

  it("accepts a missing role", async () => {
    const { service } = serviceWith({
      items: [
        {
          title: "Hackathon Finalist",
          organization: "TechFest",
          role: null,
          description: "Built a prototype in 24 hours.",
          skills: ["Python"],
        },
      ],
    });
    const result = await service.parseResume({
      mimetype: PDF_MIME,
      buffer: resumePdf(),
    });
    expect(result.items[0].role).toBeNull();
  });

  it("returns an empty items array when the resume has no meaningful experience", async () => {
    const { service } = serviceWith({ items: [] });
    const result = await service.parseResume({
      mimetype: PDF_MIME,
      buffer: resumePdf(),
    });
    expect(result.items).toEqual([]);
  });
});

describe("ResumeParserService — malformed model output", () => {
  it("rejects schema-invalid LLM output", async () => {
    const { service } = serviceWith({
      items: [{ title: "", description: "" }], // violates min-length + required fields
    });
    await expect(
      service.parseResume({ mimetype: PDF_MIME, buffer: resumePdf() }),
    ).rejects.toThrow(ResumeParseError);
  });

  it("rejects fabricated/unsupported fields (strict schema)", async () => {
    const { service } = serviceWith({
      items: [
        {
          title: "Backend Intern",
          organization: "PT Example",
          role: "Intern",
          description: "Built REST APIs.",
          skills: [],
          employer: "Invented Corp", // not part of the canonical shape
          startDate: "2020-01-01",
        },
      ],
    });
    await expect(
      service.parseResume({ mimetype: PDF_MIME, buffer: resumePdf() }),
    ).rejects.toThrow(ResumeParseError);
  });

  it("rejects a non-object envelope", async () => {
    const { service } = serviceWith({ items: "not-an-array" });
    await expect(
      service.parseResume({ mimetype: PDF_MIME, buffer: resumePdf() }),
    ).rejects.toThrow(ResumeParseError);
  });

  it("propagates provider failures unchanged", async () => {
    const provider: ResumeParserProvider = {
      parseResumeText: vi.fn().mockRejectedValue(new ProviderError("boom")),
    };
    const service = new ResumeParserService({ provider });
    await expect(
      service.parseResume({ mimetype: PDF_MIME, buffer: resumePdf() }),
    ).rejects.toThrow(ProviderError);
  });

  it("propagates provider timeouts unchanged", async () => {
    const provider: ResumeParserProvider = {
      parseResumeText: vi
        .fn()
        .mockRejectedValue(new ProviderTimeoutError("slow")),
    };
    const service = new ResumeParserService({ provider });
    await expect(
      service.parseResume({ mimetype: PDF_MIME, buffer: resumePdf() }),
    ).rejects.toThrow(ProviderTimeoutError);
  });
});

describe("ResumeParserService — upload validation", () => {
  it("rejects an unsupported MIME type", async () => {
    const { service, provider } = serviceWith({ items: [] });
    await expect(
      service.parseResume({
        mimetype: "image/png",
        buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47]),
      }),
    ).rejects.toThrow(InvalidResumeError);
    expect(provider.parseResumeText).not.toHaveBeenCalled();
  });

  it("rejects an oversized upload", async () => {
    const { service } = serviceWith(
      { items: [] },
      { maxFileBytes: 16, maxTextChars: 100_000 },
    );
    await expect(
      service.parseResume({ mimetype: PDF_MIME, buffer: resumePdf() }),
    ).rejects.toThrow(ResumeTooLargeError);
  });

  it("rejects an invalid PDF upload", async () => {
    const { service, provider } = serviceWith({ items: [] });
    await expect(
      service.parseResume({
        mimetype: PDF_MIME,
        buffer: Buffer.from("not a pdf at all", "utf8"),
      }),
    ).rejects.toThrow(InvalidResumeError);
    expect(provider.parseResumeText).not.toHaveBeenCalled();
  });

  it("rejects a PDF with no extractable text", async () => {
    const { service, provider } = serviceWith({ items: [] });
    await expect(
      service.parseResume({
        mimetype: PDF_MIME,
        buffer: buildMinimalPdf([]),
      }),
    ).rejects.toThrow(InvalidResumeError);
    expect(provider.parseResumeText).not.toHaveBeenCalled();
  });
});
