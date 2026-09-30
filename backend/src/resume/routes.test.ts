import { describe, it, expect, vi, beforeEach } from "vitest";
import Fastify, { type FastifyInstance } from "fastify";
import multipart from "@fastify/multipart";
import { registerResumeRoutes } from "./routes.js";
import { ResumeParserService } from "./service.js";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import type { ResumeParserProvider } from "./provider.js";
import { buildMinimalPdf } from "./pdf-fixture.js";

const BOUNDARY = "--------------------------testboundary12345";

function multipartBody(
  file: { buffer: Buffer; filename: string; mimetype: string } | null,
): Buffer {
  if (!file) {
    return Buffer.from(
      `--${BOUNDARY}\r\nContent-Disposition: form-data; name="note"\r\n\r\nhello\r\n--${BOUNDARY}--\r\n`,
      "utf8",
    );
  }
  return Buffer.concat([
    Buffer.from(
      `--${BOUNDARY}\r\nContent-Disposition: form-data; name="file"; filename="${file.filename}"\r\nContent-Type: ${file.mimetype}\r\n\r\n`,
      "utf8",
    ),
    file.buffer,
    Buffer.from(`\r\n--${BOUNDARY}--\r\n`, "utf8"),
  ]);
}

function buildApp(provider: ResumeParserProvider): FastifyInstance {
  const app = Fastify();
  void app.register(multipart);
  const service = new ResumeParserService({ provider });
  registerResumeRoutes(app, { service });
  return app;
}

const parsedItem = {
  title: "Backend Intern",
  organization: "PT Example",
  role: "Intern",
  description: "Built REST APIs with Node.js.",
  skills: ["TypeScript"],
};

describe("POST /resume/parse", () => {
  let okProvider: ResumeParserProvider;

  beforeEach(() => {
    okProvider = { parseResumeText: vi.fn().mockResolvedValue({ items: [parsedItem] }) };
  });

  it("parses a valid PDF upload and returns 200 with structured items", async () => {
    const app = buildApp(okProvider);
    await app.ready();
    const pdf = buildMinimalPdf(["Jane Doe", "Backend Intern at PT Example"]);

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody({
        buffer: pdf,
        filename: "cv.pdf",
        mimetype: "application/pdf",
      }),
    });

    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.items).toHaveLength(1);
    expect(body.items[0]).toMatchObject({
      title: "Backend Intern",
      organization: "PT Example",
    });
    expect(okProvider.parseResumeText).toHaveBeenCalledTimes(1);
  });

  it("returns 400 when no file is attached", async () => {
    const app = buildApp(okProvider);
    await app.ready();

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody(null),
    });

    expect(res.statusCode).toBe(400);
    expect(okProvider.parseResumeText).not.toHaveBeenCalled();
  });

  it("returns 400 for an unsupported MIME type", async () => {
    const app = buildApp(okProvider);
    await app.ready();

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody({
        buffer: Buffer.from("fake-image-bytes", "utf8"),
        filename: "photo.png",
        mimetype: "image/png",
      }),
    });

    expect(res.statusCode).toBe(400);
    expect(okProvider.parseResumeText).not.toHaveBeenCalled();
  });

  it("returns 400 for a malformed PDF", async () => {
    const app = buildApp(okProvider);
    await app.ready();

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody({
        buffer: Buffer.from("definitely not a pdf", "utf8"),
        filename: "cv.pdf",
        mimetype: "application/pdf",
      }),
    });

    expect(res.statusCode).toBe(400);
    expect(okProvider.parseResumeText).not.toHaveBeenCalled();
  });

  it("returns 413 for an oversized upload", async () => {
    const app = Fastify();
    void app.register(multipart);
    const service = new ResumeParserService({
      provider: okProvider,
      limits: { maxFileBytes: 16, maxTextChars: 100_000 },
    });
    registerResumeRoutes(app, { service });
    await app.ready();
    const pdf = buildMinimalPdf(["Jane Doe"]);

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody({
        buffer: pdf,
        filename: "cv.pdf",
        mimetype: "application/pdf",
      }),
    });

    expect(res.statusCode).toBe(413);
    expect(okProvider.parseResumeText).not.toHaveBeenCalled();
  });

  it("returns 502 when the provider fails", async () => {
    const app = buildApp({
      parseResumeText: vi.fn().mockRejectedValue(new ProviderError("down")),
    });
    await app.ready();
    const pdf = buildMinimalPdf(["Jane Doe"]);

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody({
        buffer: pdf,
        filename: "cv.pdf",
        mimetype: "application/pdf",
      }),
    });

    expect(res.statusCode).toBe(502);
  });

  it("returns 502 when the model output is schema-invalid", async () => {
    const app = buildApp({
      parseResumeText: vi.fn().mockResolvedValue({ items: [{ nope: true }] }),
    });
    await app.ready();
    const pdf = buildMinimalPdf(["Jane Doe"]);

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody({
        buffer: pdf,
        filename: "cv.pdf",
        mimetype: "application/pdf",
      }),
    });

    expect(res.statusCode).toBe(502);
  });

  it("returns 504 when the provider times out", async () => {
    const app = buildApp({
      parseResumeText: vi
        .fn()
        .mockRejectedValue(new ProviderTimeoutError("slow")),
    });
    await app.ready();
    const pdf = buildMinimalPdf(["Jane Doe"]);

    const res = await app.inject({
      method: "POST",
      url: "/resume/parse",
      headers: { "content-type": `multipart/form-data; boundary=${BOUNDARY}` },
      payload: multipartBody({
        buffer: pdf,
        filename: "cv.pdf",
        mimetype: "application/pdf",
      }),
    });

    expect(res.statusCode).toBe(504);
  });
});
