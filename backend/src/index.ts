import Fastify from "fastify";
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

const start = async () => {
  try {
    await app.listen({ host: HOST, port: PORT });
  } catch (err) {
    app.log.error(err);
    process.exit(1);
  }
};

start();
