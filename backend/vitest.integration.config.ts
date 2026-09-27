import { defineConfig } from "vitest/config";

// Opt-in real-stack integration suite.
//
// Runs ONLY the `*.live.test.ts` files, which exercise the real wiring:
//   ConversationService -> ConversationRepository -> Prisma/PostgreSQL
//   ConversationService -> QwenProvider -> Alibaba DashScope
// and a full-stack HTTP smoke path through a booted Fastify instance.
//
// These are never part of the default `pnpm test` run. Even when invoked, each
// live test self-skips unless the required environment is present (see each
// test file's gate), so `pnpm test:integration` is safe to run without a
// database or API key — it simply reports the live tests as skipped.
export default defineConfig({
  test: {
    include: ["src/**/*.live.test.ts"],
    exclude: ["**/node_modules/**", "**/dist/**"],
    // Real interviews take many real LLM round-trips to reach the
    // MAX_QUESTIONS boundary; allow generous but bounded time.
    testTimeout: 300_000,
    hookTimeout: 120_000,
  },
});
