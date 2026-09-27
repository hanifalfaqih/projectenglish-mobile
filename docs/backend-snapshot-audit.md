# Backend Snapshot Audit

## 1. Purpose

This audit establishes an evidence-based, minimal, and reproducible backend snapshot boundary for the Shipaton 2026 Next Gen Android submission. It is an audit only. No backend has been copied and no source-repository file has been modified.

Evidence labels: **VERIFIED** means directly inspected implementation or Git metadata; **OBSERVED** means present in the working tree; **INFERRED** means a constrained conclusion from verified evidence; **NEEDS DECISION**, **NEEDS INPUT**, and **NOT VERIFIED** retain unresolved matters from the product decision.

## 2. Repository Boundary

- Main Project English repository: `/Users/hanifalfaqih/Projects/project-aienglish-interview/`. It remains the product and backend source of truth.
- Shipaton delivery directory: `/Users/hanifalfaqih/Projects/project-aienglish-mobile/`. It is the separate competition artifact and may eventually contain a documented backend snapshot under `backend/`.
- Snapshot relationship: any future copied code is a fixed, traceable submission snapshot. It must not become a second development source of truth or trigger modifications to the main repository.
- **OBSERVED:** the delivery directory is not currently a Git repository; `git status` fails because no `.git` directory exists. This is a delivery-repository setup gap.

## 3. Source Repository State

| Field | Value | Evidence |
|---|---|---|
| Source repository | `/Users/hanifalfaqih/Projects/project-aienglish-interview/` | **VERIFIED** |
| Branch | `main` | **VERIFIED** |
| HEAD | `fcad746bbb26d076ed10a123eb8dd560b96dc7db` | **VERIFIED** |
| Commit message | `docs: archive M21 product decision analysis` | **VERIFIED** |
| Commit date | 2026-09-28 01:21:47 +0700 | **VERIFIED** |
| Working tree | No tracked changes; untracked `.qwen/` directory | **VERIFIED** |
| Tracking state | `main...origin/main [gone]` | **VERIFIED** |
| Snapshot date | 2026-09-28 | **VERIFIED** |
| Snapshot purpose | Shipaton 2026 Next Gen submission | **VERIFIED** |

Relevant implementation history includes `a822915` (experience reuse and early completion), `83c6d55` (spoken practice), `ec4b290` (learning-aware context), `052680f` (learning), `17e948a` (recovery), `1212e15` (review), `98c27d3` (retry), `54c494b` (feedback), `5208024` (browser voice), and `7da5ec7` (experience).

## 4. Backend Architecture

**VERIFIED:** the source is a pnpm workspace. The backend is `apps/api`, a TypeScript ESM Fastify application. Its bootstrap wires conversation, experience, feedback, retry, review, and learning services (`apps/api/src/index.ts`). The web application is a separate Next.js client in `apps/web`; it proxies relative `/api/*` paths to `API_URL` and is not required to operate the backend.

The API uses Prisma against PostgreSQL. Runtime domain state is persisted, not held only in memory. There is no API version prefix. The nominal `packages/contracts` package is effectively empty, so route schemas and service return types are the authoritative contract source.

## 5. Capability Inventory

### Experience

**VERIFIED:** `POST /experience-profiles` validates and persists a manual profile with 1-20 normalized experience items. An item has `title`, optional `organization` and `role`, `description`, and optional `skills`; aggregate input is capped at 12,000 characters. `ExperienceProfile` and ordered `ExperienceItem` records persist this data. A conversation can reference an optional profile through `experienceProfileId`.

No profile retrieval or update endpoint was observed. The backend owns item ordering and identifiers. Experience use as interview context is implemented; it is not evidence that experience intake must be in the first Android scope.

### Interview / Conversation

**VERIFIED:** `POST /conversations` creates an active persisted conversation, optionally associated with an existing experience profile. `POST /conversations/:id/turns` persists a user turn, obtains the next assistant turn from the LLM provider, atomically persists assistant output and state, and may transition the conversation to `closed`.

Conversation state is stored as `Conversation.state` JSON with `stateVersion`; transcript messages are persisted in `Message`. The service supplies the LLM with a bounded 12-message context window and may add experience and learning context. The client must not submit concurrent turns. `clientTurnId` is persisted with a database uniqueness constraint on `(conversationId, clientTurnId)`, making transport retry idempotent when the same ID is reused. `GET /conversations/:id` provides recovery after client restart.

Question sequencing, contextual follow-up, limits, and state transitions are governed by the existing conversation service and validated provider output. Exact user-facing sequencing is **DEPENDENT ON EXISTING BACKEND** and should be preserved rather than reimplemented by Android.

### Feedback

**VERIFIED:** feedback is generated only for a closed conversation. There is at most one `Feedback` per conversation, enforced by a unique `conversationId`. The generation service reads the full transcript and uses a separate LLM provider. Invalid model references are dropped or nullified before persistence. Repeating the generate request returns the existing record instead of creating a second one. `GET /conversations/:id/feedback` reads persisted feedback only.

### Retry

**VERIFIED:** retry practice is implemented as a durable, append-only `RetryPractice` artifact for a prior answer. Submission validates referenced question and answer messages. `retryClientKey` is globally unique and is the idempotency key. A newer retry supersedes the prior current retry for the same answer; rows are not deleted.

Retry feedback is generated after persistence. A provider failure can return a persisted retry with `feedbackStatus: "failed"`; `POST /conversations/:id/retries/:answerMessageId/feedback` only regenerates feedback for that failed current retry. The path does not modify the original transcript or main feedback.

### Learning

**VERIFIED:** learning derivation is implemented for a closed conversation that has feedback and a resolvable experience. One `LearningDerivation` exists per feedback record. It creates a `PracticeTrack` per experience profile and immutable qualitative `LearningSignal` records with validated feedback-item and message references.

Learning-aware context exists in the conversation service. The current web client triggers derivation as non-blocking best effort after feedback. This capability is useful but not necessary to demonstrate one interview and feedback journey; whether it belongs in the first Android scope remains **NEEDS DECISION**.

### Authentication

**VERIFIED:** no authentication, user model, session lifecycle, access token, cookie, authorization middleware, or ownership check was observed. All listed API routes are unauthenticated. Conversation IDs function as capability-like access identifiers.

**INFERRED:** a public deployment carrying real learner data would be unsafe without an explicit authentication, authorization, rate-limit, TLS, and privacy decision. This audit does not introduce mobile authentication requirements or authorize an authentication redesign.

### LLM / AI

**VERIFIED:** conversation, feedback, retry, and learning each use Qwen-compatible provider implementations. They call the chat-completions endpoint with bearer authorization, a structured JSON schema, and a default 30-second timeout. `DASHSCOPE_API_KEY` is required at API startup; `LLM_BASE_URL`, `LLM_MODEL_ID`, and `LLM_TIMEOUT_MS` configure the provider.

The server treats generated output as untrusted: it validates structured output and resolves/merges allowed references against persisted domain data before storing it. Provider errors are mapped to service/route failures, including `502` and `504` on the relevant generation paths. No fallback provider was observed.

### Database

**VERIFIED:** PostgreSQL is the sole Prisma datasource. Relevant models are `ExperienceProfile`, `ExperienceItem`, `Conversation`, `Message`, `Feedback`, `RetryPractice`, `PracticeTrack`, `LearningDerivation`, and `LearningSignal` (`apps/api/prisma/schema.prisma`). The persisted data includes learner employment narratives, interview transcripts, feedback, retries, and learning observations.

**VERIFIED BLOCKER:** `apps/api/prisma/migrations/` is ignored by `.gitignore`. The initial `0_init` migration and migration lock file exist locally but are untracked; later tracked migrations rely on tables created by that initial migration. A clean checkout cannot reproducibly apply the complete migration chain with `prisma migrate deploy`.

No reusable Prisma seed configuration was observed. No deletion endpoint, retention automation, TTL, or backup policy was observed.

### Voice / Speech

**VERIFIED:** voice support is browser-only. `apps/web/src/lib/speech.ts` uses the Web Speech API for `en-US` speech recognition and best-effort text-to-speech. There are no backend audio upload, audio storage, STT, TTS, or voice-specific endpoints, and no server-side speech provider.

For a native client, platform speech-to-text and text-to-speech would be client responsibilities. Only final recognized text would use `POST /conversations/:id/turns`. Voice remains **NEEDS DECISION** and is not included in the proposed backend boundary.

## 6. API Contract Inventory

All observed routes are unauthenticated JSON routes with no version prefix. Request and response descriptions below summarize implementation validators and service returns; Android implementation must trace exact schemas to the listed route/schema source before coding.

| Domain | Method and route | Request / response | Status and behavior | Side effects / dependencies | Minimum journey |
|---|---|---|---|---|---|
| System | `GET /health` | `200 { status: "ok" }` | No database or provider use | None | Supporting |
| Experience | `POST /experience-profiles` | `{ items: [{ title, organization?, role?, description, skills? }] }`; `201 { id }` | `400` invalid/oversize | Creates profile/items; no idempotency key | Conditional required |
| Interview | `POST /conversations` | Optional `{ experienceProfileId? }`; `201 { id, status, state }` | `404` unknown profile; `400`/`500` | Creates active conversation | Required |
| Interview | `POST /conversations/:id/turns` | `{ clientTurnId, message }`; `200 { assistantMessage, state, status, closing }` | `404`, `409` closed/in-progress, `400`, `502`, `504`, `500` | Writes user/assistant messages and state; LLM; same `clientTurnId` is idempotent | Required |
| Interview | `GET /conversations/:id` | `200` conversation, state, optional profile ID, ordered transcript | `404`, `500` | Read-only; supports active/closed recovery | Supporting |
| Feedback | `POST /conversations/:id/feedback` | No body; `201` generated or `200` existing | `404`, `409` active/in-progress, `502`, `504`, `500` | Persists one feedback; LLM | Required for feedback outcome |
| Feedback | `GET /conversations/:id/feedback` | Persisted feedback with overall, items, professional communication | `404`, `500` | Read-only | Supporting |
| Retry | `POST /conversations/:id/retries` | `{ answerMessageId, questionMessageId, retryAnswer, retryClientKey }`; `201` new or `200` duplicate | `400`, `404`, `409`, `500` | Persists/supersedes retry; LLM feedback may fail in-record | Optional |
| Retry | `GET /conversations/:id/retries/:answerMessageId` | Current retry plus original text | `404` | Read-only | Optional |
| Retry | `POST /conversations/:id/retries/:answerMessageId/feedback` | No body; regenerated retry feedback | `404`, `409`, `502`, `504`, `500` | LLM only for failed current retry | Optional |
| Review | `GET /conversations/:id/review` | Closed conversation, transcript, nullable feedback, current retries | `404`, `409`, `500` | Read-only | Optional supporting |
| Learning | `POST /conversations/:id/learning` | No body; `201` derived or `200` existing | `404`, `409`, `502`, `504`, `500` | Persists track/derivation/signals; LLM | Optional |
| Learning | `GET /conversations/:id/learning` | Track, feedback provenance, signals | `400`, `404`, `500` | Read-only | Optional |

## 7. Minimum Mobile Backend Surface

The product decision deliberately leaves the detailed practice format, experience intake, feedback outcome, and voice scope unresolved. Therefore, the following is a proposed technical boundary, not a silent product decision.

### REQUIRED

- Conversation creation, turn submission, persisted conversation state, and retrieval/recovery: the smallest actual backend capability for a multi-turn professional-English interview flow.
- PostgreSQL/Prisma schema and a complete, reproducible migration baseline: required to make the conversation engine durable and functional.
- Qwen-compatible conversation provider configuration and its structured-output validation: required by the authoritative conversation engine.
- `GET /health`: required operationally to diagnose app-to-backend connectivity.

### SUPPORTING

- Experience persistence and `POST /experience-profiles` **if** the chosen product promise requires user experience as interview context. The API permits a conversation without it, so its inclusion is conditional rather than assumed.
- Error mapping and idempotency behavior for turns: Android must persist conversation ID and retry the same `clientTurnId` after an ambiguous network failure.
- Root workspace manifest, pnpm lockfile, API package manifest, Prisma client generation, and API tests/configuration: required to reproduce the API rather than merely copy source files.

### OPTIONAL

- Main feedback endpoints: recommended only after the product decision explicitly defines feedback as the end-of-journey outcome. They are implemented and coherent, but feedback is not yet a locked mobile behavior.
- Review endpoint: a convenient aggregate completed-session read; not necessary if the first scope only displays newly generated feedback.
- Retry practice and retry-feedback recovery: valuable deliberate practice, but expands the first mobile experience.
- Learning derivation and retrieval: existing durable personalization capability; it adds a further LLM call and requires an experience profile.
- Browser web client and browser speech utilities: not needed by native Android.

### OUT OF SCOPE

- `apps/web` and its Next.js proxy/UI, including browser-specific speech implementation.
- Voice backend work, audio processing, or storage: none exists to snapshot.
- Authentication redesign, user accounts, authorization, payments, RevenueCat, CORS redesign, deployment infrastructure, and new API endpoints.
- Unrelated historical product documentation, live-evidence artifacts, and the untracked `.qwen/` directory.

## 8. Runtime Dependencies

| Component | Purpose | Required? | Local setup? | External service? | Secret required? | Public OSS concern | Notes |
|---|---|---:|---:|---:|---:|---|---|
| Node.js `>=20` | API runtime | Yes | Yes | No | No | Version not exact-pinned | Root `package.json` |
| pnpm / lockfile v9 | Workspace install | Yes | Yes | No | No | No `packageManager` pin | Reproducibility relies on lockfile |
| Fastify | HTTP API | Yes | Yes | No | No | MIT | API server |
| Prisma Client/CLI | PostgreSQL ORM and migration | Yes | Yes | No | `DATABASE_URL` | Apache-2.0 | Complete migration baseline currently missing |
| PostgreSQL | Durable domain data | Yes | Yes or hosted | Yes | Connection credential normally required | Learner/employment data risk | No seed found |
| Zod | Input/output validation | Yes | Yes | No | No | MIT | Route schemas |
| Qwen-compatible provider | Conversation generation | Yes for interview turns | No | Yes | `DASHSCOPE_API_KEY` | Service terms **NOT VERIFIED** | Default model is configurable |
| Qwen feedback/retry/learning calls | Extended capabilities | Only when selected | No | Yes | Same key | Service terms **NOT VERIFIED** | Optional in initial scope |
| Browser Web Speech API | Web STT/TTS | No | No | Browser platform | No | Not applicable to Android backend | Do not copy for Android |
| Next.js/React | Existing web UI/proxy | No | No | No | `API_URL` for web proxy | Current dependency advisories | Excluded from backend snapshot |

No Docker/Compose, CI workflow, deployment manifest, infrastructure-as-code, Redis, queue, object storage, email, payment, analytics, monitoring, or server-side speech integration was observed. Production deployment assumptions are **NOT VERIFIED**.

## 9. Environment Variables

Names only; no values were read or copied.

| Variable | Consumer and purpose | Requirement |
|---|---|---|
| `DATABASE_URL` | Prisma PostgreSQL datasource | Required |
| `DASHSCOPE_API_KEY` | Qwen-compatible providers | Required for API startup |
| `LLM_BASE_URL` | Qwen-compatible base URL | Optional default |
| `LLM_MODEL_ID` | Model selection | Optional default |
| `LLM_TIMEOUT_MS` | Provider timeout | Optional default, 30,000 ms |
| `HOST` | API listener address | Optional default `0.0.0.0` |
| `PORT` | API listener port | Optional default `3001` |
| `RUN_LIVE_INTEGRATION` | Enables live integration tests only when `1` | Test-only |
| `API_URL` | Existing web proxy target only | Excluded with web client |

## 10. External Services

- PostgreSQL: **VERIFIED** required persistence service.
- Alibaba Cloud Model Studio/DashScope Qwen-compatible API: **VERIFIED** LLM external dependency for conversation and the existing optional generation capabilities.
- Android speech service: **NOT VERIFIED** and not a current backend dependency. It is a future client/platform decision if voice is selected.
- RevenueCat: **NOT OBSERVED** in the source backend. Its required Shipaton integration remains an Android/product implementation decision and must not be invented in this snapshot.

## 11. Security / OSS Audit

### Findings

- **HIGH, VERIFIED:** no authentication, authorization, ownership isolation, or rate limiting exists. The API listens on `0.0.0.0:3001`; IDs can retrieve transcripts, feedback, retries, and learning data. Do not expose it publicly with real learner data without a separate security and privacy decision.
- **HIGH, VERIFIED:** Prisma migration reproducibility is broken in a clean checkout because the initial migration is ignored and untracked while later migrations depend on it.
- **VERIFIED:** `apps/api/.env` exists locally, is ignored, and is not Git-tracked. It contains populated database and DashScope configuration. Its values were not read, copied, or documented.
- **VERIFIED:** Git history has no commits for `apps/api/.env`, `.env`, or `.env.local`. This is not proof that all historical secrets are absent; a dedicated history-capable secret scanner was not available. **NOT VERIFIED:** complete historical-secret absence.
- **OBSERVED:** source documentation contains internal product, contract, and live-evidence materials. Treat the source `docs/` directory as non-public by default; do not copy it wholesale.
- **VERIFIED:** data models persist professional experience narratives, transcripts, feedback, retry answers, and learning signals. No field encryption, deletion API, retention automation, or TTL was observed.
- **NOT VERIFIED:** all assets, test fixtures, and historical documentation are suitable for public release. A path-by-path release review remains required.

## 12. Dependency / License Audit

- **VERIFIED:** the source repository has no `LICENSE`, `NOTICE`, or third-party attribution file, and root/workspace packages are private. Rights to publish a copied backend under an open-source license are therefore **NEEDS DECISION**; do not assume source ownership or relicensing authority.
- **VERIFIED:** direct backend dependencies are predominantly permissive: Fastify and Zod are MIT; Prisma is Apache-2.0. The pnpm lockfile must be preserved for reproducibility.
- **OBSERVED:** the complete workspace dependency inventory includes an LGPL-3.0-or-later `sharp`/libvips native binary and CC-BY-4.0 `caniuse-lite`. These are web/build dependencies and are excluded with `apps/web`; any later redistribution must retain required notices and receive a release-specific license review.
- **VERIFIED:** dependency audit reported three high and four moderate advisories in the whole workspace, including vulnerable `postcss`, `deepmerge-ts`, and dev-only Vitest packages. The web dependency path is excluded, but the backend snapshot must run a fresh dependency audit after its manifest is selected.
- **NOT VERIFIED:** DashScope/Alibaba service terms, account terms, data-processing terms, regional transfer terms, and output-use rights. These are external constraints and must be accepted by the eventual operator.

## 13. Snapshot Boundary

### Decision: STOP BEFORE COPYING

The backend must not yet be copied into `backend/`.

The selected source revision is traceable, and the technical boundary is sufficiently understood, but a public, self-contained, reproducible snapshot cannot be responsibly created from it because:

1. The required initial Prisma migration is untracked and excluded by source `.gitignore`, preventing an exact revision-backed fresh database setup.
2. The source repository has no declared license or redistribution grant; public open-source publication of selected backend code is not authorized by repository evidence.
3. The intended Android journey has not selected whether experience intake and feedback are part of its minimum scope. This does not block documenting the core conversation boundary, but it does block a precise minimal feature snapshot.
4. The delivery directory is not yet a Git repository, so it cannot provide the required public-repository traceability or history review.

No workaround should copy untracked migrations, secrets, or source documentation without explicit review. No source-repository change is proposed by this audit.

## 14. Snapshot Contents

No files have been copied.

When the blockers are resolved, the proposed selection is:

- `apps/api/src/` limited to bootstrap, database client, conversation, experience only if selected, and shared validation/provider code required by that surface.
- `apps/api/prisma/schema.prisma` plus a complete, tracked, reproducible migration baseline.
- `apps/api/package.json`, API TypeScript/test configuration, and required tests for the selected surface.
- Root `package.json`, `pnpm-workspace.yaml`, and `pnpm-lock.yaml`, reduced only if a clean independent backend package is demonstrated equivalent. Avoid refactoring merely for Android.
- Safe examples and new submission-local setup documentation, never source `.env` files or secret-bearing configuration.

Feedback, review, retry, and learning source should be selected only after the product decision confirms their place in the initial journey. `apps/web`, browser speech code, source docs, `node_modules`, `.qwen/`, ignored `.env` files, and database files are intentionally excluded.

## 15. Known Risks

- No public-source license/redistribution decision exists for the source backend.
- Current migrations cannot reconstruct a clean database from the source revision.
- The unauthenticated capability-ID API is inappropriate for public exposure with real learner data.
- DashScope and PostgreSQL require external access and secrets; a public repository cannot be fully runnable without operator-provided configuration.
- LLM generation has no observed fallback provider and can fail with `502`/`504` on generation endpoints.
- No exact Node/pnpm version pin or deployment configuration exists.
- The mobile delivery directory has no Git metadata, preventing public repository and history-hardening readiness.
- Voice is browser-only in the source and remains an open Android product decision.
- Existing backend features can create scope pressure; their presence is not a product reason to include them.

## 16. Unresolved Decisions

- **NEEDS INPUT:** confirm the entrant has authority to release the selected source code and assets under an open-source license compatible with Shipaton requirements.
- **NEEDS DECISION:** determine the Android minimum journey: whether it includes experience intake, feedback, review, retry, and learning, beyond mandatory multi-turn practice.
- **NEEDS DECISION:** choose the RevenueCat monetization flow independently of existing backend capabilities. No RevenueCat implementation exists in the source backend.
- **NEEDS DECISION:** decide whether the API is private to a controlled demo environment or requires a separately scoped identity/security design. Do not imply the existing unauthenticated API is production-safe.
- **NEEDS DECISION:** decide whether voice is a core Android feature. The existing web implementation does not define a native voice contract.
- **NEEDS INPUT:** establish how to produce a complete, tracked migration baseline without modifying the source repository in this task.
- **NOT VERIFIED:** public suitability of every source asset, documentation file, dependency, historical commit, and external-service term.
- **NOT VERIFIED:** runtime deployment, device behavior, RevenueCat behavior, and real-device verification.

## 17. Recommended Next Step

Resolve the two snapshot blockers before any copy: obtain explicit open-source/relicensing authority for the selected backend, and provide a complete tracked migration baseline or another reproducible clean-database procedure. In parallel, confirm the smallest mobile journey, at minimum whether it includes experience intake and generated feedback. Then initialize the delivery directory as its own Git repository, perform a fresh secret/license review of only the selected files, and re-audit before copying.
