import Fastify from "fastify";
import multipart from "@fastify/multipart";
import { prisma } from "./db/prisma.js";
import { ConversationRepository } from "./conversation/repository.js";
import { ConversationService } from "./conversation/service.js";
import { QwenProvider, createQwenProviderConfig } from "./conversation/provider/qwen.js";
import { registerConversationRoutes } from "./conversation/routes.js";
import { ExperienceProfileRepository } from "./experience/repository.js";
import { FeedbackRepository } from "./feedback/repository.js";
import { FeedbackService } from "./feedback/service.js";
import {
  QwenFeedbackProvider,
  createQwenFeedbackProviderConfig,
} from "./feedback/provider.js";
import { registerFeedbackRoutes } from "./feedback/routes.js";
import { RetryRepository } from "./retry/repository.js";
import { RetryService } from "./retry/service.js";
import {
  QwenRetryFeedbackProvider,
  createQwenRetryFeedbackProviderConfig,
} from "./retry/provider.js";
import { registerRetryRoutes } from "./retry/routes.js";
import { QwenAsrProvider, createQwenAsrProviderConfig } from "./voice/provider.js";
import { QwenTtsSynthesizer, createQwenTtsConfig } from "./voice/tts.js";
import { VoiceTurnService } from "./voice/service.js";
import {
  registerTranscriptionRoutes,
  registerVoiceRoutes,
} from "./voice/routes.js";
import {
  DEFAULT_RESUME_LIMITS,
  ResumeParserService,
} from "./resume/service.js";
import {
  QwenResumeParserProvider,
  createQwenResumeParserConfig,
} from "./resume/provider.js";
import { registerResumeRoutes } from "./resume/routes.js";

const HOST = process.env.HOST ?? "0.0.0.0";
const PORT = Number(process.env.PORT) || 3001;

const app = Fastify({ logger: true });

app.get("/health", async () => {
  return { status: "ok" };
});

const repository = new ConversationRepository(prisma);
const experienceProfiles = new ExperienceProfileRepository(prisma);
const provider = new QwenProvider(createQwenProviderConfig());
const service = new ConversationService({
  repository,
  provider,
  experienceProfiles,
});

registerConversationRoutes(app, { repository, service, experienceProfiles });

const feedbackRepository = new FeedbackRepository(prisma);
const feedbackProvider = new QwenFeedbackProvider(
  createQwenFeedbackProviderConfig(),
);
const feedbackService = new FeedbackService({
  conversationRepository: repository,
  feedbackRepository,
  provider: feedbackProvider,
  experienceProfiles,
});

registerFeedbackRoutes(app, { service: feedbackService });

const retryRepository = new RetryRepository(prisma);
const retryProvider = new QwenRetryFeedbackProvider(
  createQwenRetryFeedbackProviderConfig(),
);
const retryService = new RetryService({
  conversationRepository: repository,
  feedbackRepository,
  retryRepository,
  provider: retryProvider,
  experienceProfiles,
});

registerRetryRoutes(app, { service: retryService });

// Resume/CV parser: parse-only endpoint (no persistence). The multipart
// plugin enforces the upload byte limit at the transport layer; the service
// re-checks it alongside MIME and PDF validity.
const resumeProvider = new QwenResumeParserProvider(
  createQwenResumeParserConfig(),
);
const resumeService = new ResumeParserService({ provider: resumeProvider });

registerResumeRoutes(app, { service: resumeService });

// Voice turns: Android-captured PCM → Qwen3-ASR transcript → the same
// authoritative ConversationService.processTurn behind POST /turns.
// Server-side Qwen credentials only; the client never sees them.
const asrProvider = new QwenAsrProvider(createQwenAsrProviderConfig());
const ttsSynthesizer = new QwenTtsSynthesizer(createQwenTtsConfig());
const voiceTurnService = new VoiceTurnService({
  conversationRepository: repository,
  conversationService: service,
  asrProvider,
  tts: ttsSynthesizer,
});

registerVoiceRoutes(app, { service: voiceTurnService });

// Transcription only (no conversation turn, no persistence). Targeted retry
// transcribes the spoken answer here, then submits the transcript to
// POST /conversations/:id/retries so the retry stays a RetryPractice
// artifact and the original conversation is never mutated.
registerTranscriptionRoutes(app, { service: voiceTurnService });

const start = async () => {
  try {
    await app.register(multipart, {
      limits: { fileSize: DEFAULT_RESUME_LIMITS.maxFileBytes },
    });
    await app.listen({ host: HOST, port: PORT });
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
};

start();
