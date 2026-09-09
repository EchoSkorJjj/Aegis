# Aegis AI Service

The internal, **Python / FastAPI** service that holds Aegis's AI logic — **assess** and **draft**
(with RAG) — called by `aegis-core` over REST. It is **not** exposed through the gateway and never
sees a browser or a user JWT: `aegis-core` calls it with a shared **service token**. See the root
[`ARCHITECTURE.md`](../../ARCHITECTURE.md) and [`docs/adr/0001-aegis-core-language.md`](../../docs/adr/0001-aegis-core-language.md).

> **Status: scaffold.** The endpoints, the contract, service-token auth, and an LLM **provider port**
> exist; assess/draft return deterministic **stubs** (no LLM call). Real prompts, RAG, and the
> Anthropic client land in BUILD_PLAN step 7.

## API (the contract aegis-core mirrors)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/actuator/health` | public | Liveness |
| GET | `/actuator/prometheus` | public | Metrics |
| POST | `/v1/assess` | `X-Service-Token` | Assess a finding → `{material, rationale, suggested_severity, citations}` |
| POST | `/v1/draft` | `X-Service-Token` | Draft a response → `{draft_type, content, citations}` |

FastAPI publishes the OpenAPI spec at `/openapi.json` (`/docs` for Swagger UI) — that spec is the
source of truth for `aegis-core`'s `AiDtos`.

## Design notes (future-proofing)

- **Stateless for the MVP** — grounding material is passed in each request; there is no database.
  When the evidence corpus grows, this service gains its *own* vector store (pgvector) that it owns.
- **Provider behind a port** — everything depends on the `LlmClient` protocol (`llm/`); the
  Anthropic client is one implementation, swappable without touching the endpoints.
- **Internal-only** — no CORS, no gateway route; reachable only from `aegis-core` on the compose network.

### Key environment variables

| Var | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8083` | HTTP port |
| `SERVICE_TOKEN` | `dev-internal-token-change-me` | Shared token aegis-core must present |
| `LLM_PROVIDER` | `stub` | `stub` or `anthropic` (needs the `llm` extra + a key) |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` | — / `claude-sonnet-5` | LLM credentials/model (step 7) |
| `TRACING_ENABLED` / `ZIPKIN_ENDPOINT` | `false` / `…:9411/api/v2/spans` | Opt-in distributed tracing |

## Layout

```
src/ai_service/
  main.py           app factory
  schemas.py        the wire contract (mirrors aegis-core's AI-port DTOs)
  core/             plumbing — config, security (service token), errors, observability
  api/              HTTP layer — routes (thin: validate → delegate → return)
  capabilities/     the AI features — assess, draft (prompts + RAG + guardrails grow here)
  llm/              provider port — base (protocol), stub, factory (+ anthropic later)
```

Dependencies flow one way: `api` → `capabilities` → `llm`, with everything resting on `core` and
`schemas`. A real provider is a new `llm/anthropic.py` plus a factory switch; RAG or guardrails are
new modules the capabilities call — neither touches the API or the contract.

## Develop & test

```bash
# from backend/ai-service
python -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'          # add ,llm and/or ,tracing as needed
uvicorn ai_service.main:app --reload --port 8083
pytest                            # no Docker, no API key required
```
