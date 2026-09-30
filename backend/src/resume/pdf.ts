import { extractText } from "unpdf";

/**
 * In-memory PDF text extraction (no OCR, no persistence).
 *
 * The uploaded file is never written to disk and its contents are never
 * logged; extraction happens fully in memory and the buffer is released with
 * the request scope.
 */
export class PdfExtractionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "PdfExtractionError";
  }
}

const PDF_MAGIC = "%PDF-";

/** Quick header check so non-PDF bytes fail fast with a clear error. */
export function looksLikePdf(buffer: Buffer): boolean {
  if (buffer.length < PDF_MAGIC.length) return false;
  return buffer.subarray(0, PDF_MAGIC.length).toString("latin1") === PDF_MAGIC;
}

/**
 * Extract plain text from a PDF buffer. Throws PdfExtractionError when the
 * input is not a PDF or cannot be read. Returns the trimmed text, which may
 * be empty when the PDF has no extractable text layer (scanned images are
 * out of scope — no OCR).
 */
export async function extractPdfText(buffer: Buffer): Promise<string> {
  if (!looksLikePdf(buffer)) {
    throw new PdfExtractionError("Upload is not a readable PDF document");
  }
  let text: string | string[];
  try {
    // Copy into a plain Uint8Array: pdf.js owns the bytes it parses.
    const result = await extractText(new Uint8Array(buffer), {
      mergePages: true,
    });
    text = result.text;
  } catch (err) {
    throw new PdfExtractionError(
      "PDF could not be read or contains no text layer",
      err,
    );
  }
  const merged = Array.isArray(text) ? text.join("\n") : text;
  return merged.trim();
}
