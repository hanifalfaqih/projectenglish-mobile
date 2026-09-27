# Project English — Backend (Shipaton submission snapshot)

Curated submission snapshot of the Project English interview backend: a
Fastify + Prisma (PostgreSQL) API that owns conversation state, turn
sequencing, and feedback generation for the experience-grounded interview
journey. The main development repository remains the source of truth; this
directory is a fixed artifact and is not developed further here.

Covered API surface: `GET /health`, `POST /experience-profiles`,
`POST /conversations`, `POST /conversations/:id/turns`,
`GET /conversations/:id`, `POST /conversations/:id/feedback`,
`GET /conversations/:id/feedback`. Retry, review, and learning endpoints
from the main repository are intentionally not part of this snapshot.

## Prerequisites

- Node.js >= 20
- pnpm >= 9
- PostgreSQL >= 14 (operator-provided; see below)
- A DashScope / Qwen-compatible API key (operator-provided; see below)

## Install

```sh
pnpm install
pnpm prisma:generate
```

## Environment setup

```sh
cp .env.example .env
```

Edit `.env`:

| Variable | Required | Purpose |
|---|---|---|
| `DATABASE_URL` | Yes | Prisma PostgreSQL connection string |
| `DASHSCOPE_API_KEY` | Yes | Qwen-compatible provider key (server exits without it) |
| `LLM_BASE_URL` | No | Provider base URL (has a default) |
| `LLM_MODEL_ID` | No | Model id (has a default) |
| `LLM_TIMEOUT_MS` | No | Provider timeout in ms (default 30000) |
| `HOST` / `PORT` | No | Listener address (defaults `0.0.0.0` / `3001`) |

Never commit `.env`.

## Database setup

```sh
pnpm prisma:migrate:deploy
```

This applies the complete migration chain in `prisma/migrations/` (baseline
`0_init` through the latest migration, pinned by `migration_lock.toml`).

## Run

```sh
pnpm dev    # watch mode (tsx)
pnpm build  # compile to dist/
pnpm start  # run compiled output (requires pnpm build first)
```

## Test

```sh
pnpm test             # deterministic unit suite (no DB, no provider calls)
pnpm test:integration # env-gated live tests; self-skip without DB + API key
pnpm typecheck        # tsc --noEmit
```

## Health check

```sh
curl http://localhost:3001/health
# {"status":"ok"}
```

## External services

PostgreSQL and the DashScope/Qwen-compatible LLM API are operator-provided
services under their own terms. They are not part of this project's MIT
license: operating this backend requires your own database, your own API key,
and your independent acceptance of those services' terms.

## License

MIT — see the repository root `LICENSE`.
