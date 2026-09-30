import type { FastifyInstance } from "fastify";
import {
  ProviderError,
  ProviderTimeoutError,
} from "../conversation/service.js";
import {
  InvalidResumeError,
  ResumeParseError,
  ResumeTooLargeError,
  type ResumeParserService,
} from "./service.js";

/**
 * POST /resume/parse — multipart `file` (PDF only) → structured experience
 * items. Parse-only: nothing is persisted. The caller maps `items` into the
 * existing POST /experience-profiles contract after user review.
 *
 * Requires `@fastify/multipart` to be registered on the app. Error mapping
 * follows the existing API conventions: 400 invalid upload, 413 oversized,
 * 502 provider/parse failure, 504 provider timeout, 500 unexpected.
 */
export function registerResumeRoutes(
  app: FastifyInstance,
  deps: { service: ResumeParserService },
): void {
  app.post("/resume/parse", async (request, reply) => {
    let part: Awaited<ReturnType<typeof request.file>>;
    try {
      part = await request.file();
    } catch (err) {
      if (isTooLargeError(err)) {
        return reply.status(413).send({ error: "Resume file too large" });
      }
      return reply.status(400).send({ error: "Invalid request" });
    }
    if (!part) {
      return reply.status(400).send({ error: "Invalid request" });
    }

    let buffer: Buffer;
    try {
      buffer = await part.toBuffer();
    } catch (err) {
      if (isTooLargeError(err)) {
        return reply.status(413).send({ error: "Resume file too large" });
      }
      return reply.status(400).send({ error: "Invalid request" });
    }

    try {
      const result = await deps.service.parseResume({
        mimetype: part.mimetype,
        buffer,
      });
      // Uploaded bytes stay in request scope; nothing is persisted or logged.
      return reply.status(200).send(result);
    } catch (err) {
      if (err instanceof ResumeTooLargeError) {
        return reply.status(413).send({ error: "Resume file too large" });
      }
      if (err instanceof InvalidResumeError) {
        return reply.status(400).send({ error: err.message });
      }
      if (err instanceof ProviderTimeoutError) {
        return reply.status(504).send({ error: "Provider timeout" });
      }
      if (err instanceof ProviderError || err instanceof ResumeParseError) {
        return reply.status(502).send({ error: "Provider error" });
      }
      request.log.error(err);
      return reply.status(500).send({ error: "Internal server error" });
    }
  });
}

function isTooLargeError(err: unknown): boolean {
  return (
    typeof err === "object" &&
    err !== null &&
    "code" in err &&
    (err as { code: unknown }).code === "FST_REQ_FILE_TOO_LARGE"
  );
}
