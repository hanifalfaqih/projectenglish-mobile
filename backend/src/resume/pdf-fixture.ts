/**
 * Minimal deterministic PDF builder for resume-parser tests.
 *
 * Generates a valid single-page PDF (Helvetica text lines) with correct xref
 * offsets, so `unpdf` extraction behaves exactly as it does for real files.
 * No external PDF library is needed and nothing touches the network.
 */
export function buildMinimalPdf(lines: string[]): Buffer {
  let content = "BT /F1 14 Tf 72 740 Td 18 TL";
  for (const [i, line] of lines.entries()) {
    const esc = line
      .replace(/\\/g, "\\\\")
      .replace(/\(/g, "\\(")
      .replace(/\)/g, "\\)");
    content += i === 0 ? ` (${esc}) Tj` : ` T* (${esc}) Tj`;
  }
  content += " ET";
  const stream = Buffer.from(content, "latin1");

  const objects: Buffer[] = [];
  objects[1] = Buffer.from("<< /Type /Catalog /Pages 2 0 R >>", "latin1");
  objects[2] = Buffer.from(
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "latin1",
  );
  objects[3] = Buffer.from(
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    "latin1",
  );
  objects[4] = Buffer.concat([
    Buffer.from(`<< /Length ${stream.length} >>\nstream\n`, "latin1"),
    stream,
    Buffer.from("\nendstream", "latin1"),
  ]);
  objects[5] = Buffer.from(
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    "latin1",
  );

  let out = Buffer.from("%PDF-1.4\n", "latin1");
  const offsets = [0];
  for (let i = 1; i <= 5; i++) {
    offsets[i] = out.length;
    out = Buffer.concat([
      out,
      Buffer.from(`${i} 0 obj\n`, "latin1"),
      objects[i],
      Buffer.from("\nendobj\n", "latin1"),
    ]);
  }
  const xrefPos = out.length;
  let xref = "xref\n0 6\n0000000000 65535 f \n";
  for (let i = 1; i <= 5; i++) {
    xref += `${String(offsets[i]).padStart(10, "0")} 00000 n \n`;
  }
  return Buffer.concat([
    out,
    Buffer.from(
      `${xref}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xrefPos}\n%%EOF`,
      "latin1",
    ),
  ]);
}
