import { describe, it, expect } from "vitest";
import { extractPdfText, looksLikePdf, PdfExtractionError } from "./pdf.js";
import { buildMinimalPdf } from "./pdf-fixture.js";

describe("extractPdfText", () => {
  it("extracts text from a valid PDF", async () => {
    const pdf = buildMinimalPdf([
      "Jane Doe",
      "Backend Intern at PT Example",
      "Built REST APIs with Node.js.",
    ]);
    const text = await extractPdfText(pdf);
    expect(text).toContain("Jane Doe");
    expect(text).toContain("Backend Intern at PT Example");
    expect(text).toContain("Built REST APIs with Node.js.");
  });

  it("rejects bytes that are not a PDF", async () => {
    await expect(
      extractPdfText(Buffer.from("just some text, not a pdf", "utf8")),
    ).rejects.toThrow(PdfExtractionError);
  });

  it("rejects a malformed PDF that cannot be read", async () => {
    // Valid magic header but truncated/corrupt body.
    const corrupt = Buffer.concat([
      Buffer.from("%PDF-1.4\n", "latin1"),
      Buffer.from([0x00, 0x01, 0x02, 0xff, 0xfe, 0x00, 0x99]),
    ]);
    expect(looksLikePdf(corrupt)).toBe(true);
    await expect(extractPdfText(corrupt)).rejects.toThrow(PdfExtractionError);
  });

  it("returns empty text for a PDF with no text content", async () => {
    const pdf = buildMinimalPdf([]);
    // "BT ... ET" with no Tj operators yields no extractable text.
    const text = await extractPdfText(pdf);
    expect(text).toBe("");
  });
});
