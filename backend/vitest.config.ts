import { defineConfig } from "vitest/config";

// Default (deterministic) API test suite.
//
// Includes all unit + deterministic integration tests. These use in-memory
// fakes/stubs and never touch PostgreSQL or the real Qwen provider, so the
// default `pnpm test` stays fast and deterministic.
//
// Live, environment-gated tests use the `*.live.test.ts` suffix and are
// excluded here. Run them explicitly via `vitest.integration.config.ts`
// (`pnpm test:integration`).
export default defineConfig({
  test: {
    include: ["src/**/*.test.ts"],
    exclude: ["**/node_modules/**", "**/dist/**", "src/**/*.live.test.ts"],
  },
});
