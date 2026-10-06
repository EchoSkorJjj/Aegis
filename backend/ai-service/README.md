# Aegis AI Service

The internal Python / FastAPI service for **assessment and response drafting**. Requests carry a
shared `X-Service-Token`, not a user JWT. Compose gives this service no host port and no gateway
route; only `aegis-core` shares its Docker network.

**Status: Story 1 LLM integration layer implemented.** The service supports an offline stub and a
Groq provider, versioned prompts, strict output validation, source-resolved citations, and local
rate/token controls. The default remains `stub`, so development and tests need no API key.
RAG, persisted findings, human approvals, and action execution are not implemented. Core's
`HttpAiClient.assess` / `draft` are still unimplemented; the application UI does not yet invoke this
layer as part of its pipeline. The service only returns proposed assessments and draft text.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/actuator/health` | Public | Liveness |
| GET | `/actuator/prometheus` | Public | HTTP and LLM metrics |
| POST | `/v1/assess` | `X-Service-Token` | `{finding_id, advisory_text, service_context?}` → `{material, rationale, suggested_severity, citations}` |
| POST | `/v1/draft` | `X-Service-Token` | `{finding_id, draft_type, context?}` → `{draft_type, content, citations}` |

`finding_id` is an opaque string. Draft types are `risk_ticket`, `upgrade`, and `memo`; other values
return 400. Snake-case fields and response shapes continue to mirror core's `AiDtos`. FastAPI serves
the OpenAPI specification at `/openapi.json` and Swagger UI at `/docs`.

The generic draft prompt supplies the integration contract. The later type-specific response
stories still need to implement their business workflows.

## Provider, prompts, and validation

Capabilities depend on a structured-generation helper and the `LlmClient` port. Groq is an HTTP
adapter using the existing `httpx` dependency; no Groq SDK or optional package extra is required.
The `anthropic` provider remains explicitly unimplemented, including when the reserved `llm` extra
is installed. Unknown providers or Groq without a key fail at startup.

Prompts are package resources, loaded with `importlib.resources` and identified as `assess.v2`,
`draft.v2`, `draft_risk_ticket.v2` and `draft_memo.v2`. Untrusted advisory and service text is placed in numbered, escaped data blocks. Embedded
delimiters cannot close those blocks. Prompts instruct the model to treat their content as data,
and never to perform matching, final scoring, decisions, or execution.

The model returns an internal Pydantic schema with citation IDs. A schema transformer makes every
object closed (`additionalProperties: false`) and every property required for strict structured
output. Responses are validated locally too: malformed JSON, forbidden fields, invalid severity,
unknown citation IDs, and material assessments without citations are rejected. Citations are
resolved from the redacted input snippets sent to the model, each at most 1,000 characters; the model
does not supply citation text. The public contract continues to return those snippets as strings.

There is one retry for invalid model output and at most two status retries for 429/5xx responses.
Backoff honors `Retry-After` within **one overall deadline of at most 20 seconds**, shared by HTTP
attempts, validation retries, and retry waits. Other 4xx responses and timeouts are not retried.
Errors use the existing `{timestamp, status, error, message, path}` shape:

- **400:** invalid request or oversized input; text is never silently truncated.
- **400:** recognisable source control instructions; no provider call and no source text in the error.
- **502:** invalid provider output after the validation retry.
- **503:** exhausted local limits/budget, timeout, or provider unavailability.

Logs contain operational metadata, including prompt version, rather than prompts, keys, generated
text, or raw provider error bodies. Metrics record requests by provider/model/capability/outcome,
token usage by direction, latency, and prompt versions. The Compose Prometheus container is on a
different network and does not scrape ai-service; the metrics endpoint can be inspected locally.

### Guardrails (E8 story 8)

`capabilities/_guardrails.py` screens advisory and service context for recognisable instruction
attacks, including role/control delimiters, requests to ignore prior rules, reveal secrets/prompts,
or bypass approval. Screening normalises Unicode compatibility characters and invisible format
controls and runs before passage splitting. Rejection is a generic 400; aegis-core falls back to
its deterministic draft without filing, opening or sending anything.

Whole sources are redacted before prompt splitting. Recognisable private keys, credential-bearing
URLs, Bearer/Basic credentials, common API/cloud/VCS tokens, JWTs, labelled passwords/tokens,
emails, SSNs, international phone numbers and labelled personal names/addresses/identity/contact
fields become `[REDACTED]`. Generated narratives are redacted and revalidated against their schema
bounds. Server-resolved citations use the same protected passages, so they cannot reintroduce raw
matches. No original-to-redacted mapping or text is logged. The policy is mirrored in aegis-core's
`platform.common.SensitiveText`, protecting its deterministic evidence, fallback and export paths too.

Prompts forbid reconstructing redacted values or following encoded/embedded instructions. The
provider is given no tools; tool/function responses, unknown execution fields and selected claims
that Aegis has already filed, opened, merged or sent a response are invalid output. The existing
single validation retry never echoes that output into its next prompt. Human decisions and all
execution remain exclusively in aegis-core's existing workflow.

Pattern matching cannot detect every secret, unlabelled name/address, encoding or semantic attack.
Escaping and system instructions do not guarantee model obedience. No-tools execution, closed
schemas, server-resolved citations, deterministic business facts and human approval remain
independent safeguards. These guards protect the AI/draft boundaries, not raw ingestion storage.

## Configuration and cost controls

Settings read environment variables and `.env` in the process's working directory. Compose reads
the repository-root `.env` and forwards the provider and limit settings below. Keep real keys in
ignored `.env` files or your deployment secret store; never commit or log them.

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8083` | Container HTTP port; local Uvicorn uses its `--port` argument |
| `SERVICE_TOKEN` | `dev-internal-token-change-me` | Token required on assess/draft requests |
| `LLM_PROVIDER` | `stub` | `stub` or `groq`; `anthropic` is not implemented |
| `GROQ_API_KEY` | Empty | Groq credential, stored as a secret setting |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Model to request |
| `GROQ_BASE_URL` | `https://api.groq.com/openai/v1` | Trusted provider base URL; another server must support the same schema/reasoning fields |
| `LLM_TIMEOUT_SECONDS` | `20` | Overall generation deadline, maximum 20 seconds |
| `LLM_MAX_OUTPUT_TOKENS` | `1000` | Maximum completion tokens per attempt |
| `LLM_MAX_INPUT_CHARS` | `16000` | Maximum request input length |
| `LLM_MAX_REQUESTS_PER_MINUTE` | `25` | Provider attempts per rolling minute |
| `LLM_MAX_TOKENS_PER_MINUTE` | `7000` | Estimated/actual tokens per rolling minute |
| `LLM_DAILY_TOKEN_BUDGET` | `150000` | Token budget for the UTC calendar day |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` | Empty / `claude-sonnet-5` | Reserved settings for the future provider |
| `TRACING_ENABLED` / `ZIPKIN_ENDPOINT` | `false` / `http://localhost:9411/api/v2/spans` | Optional distributed tracing |

The service reserves estimated input tokens plus the maximum output tokens **before each provider
attempt**, including retries. Successful usage reconciles the reservation with the provider's token
counts. When usage is unknown, the estimate remains charged conservatively. Local limits reject
calls before sending them, so retry attempts cannot bypass the budget.

These are token controls, not a currency billing cap. They share state across assess and draft
within **one process**. State resets on restart; multiple workers or replicas do not share budgets.
Provider organization quotas may also include other clients or deployments. Run a single worker
for this development setup; distributed/persistent accounting remains future work. The input
estimate is approximate, and changing models can change actual token usage.

## Develop and test

Python 3.12+ is required. From `backend/ai-service`, on Windows PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e '.[dev]'
.\.venv\Scripts\ruff.exe check .
.\.venv\Scripts\pytest.exe -q
.\.venv\Scripts\python.exe -m uvicorn ai_service.main:app --reload --host 127.0.0.1 --port 8083
```

On macOS/Linux:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
ruff check .
pytest -q
uvicorn ai_service.main:app --reload --host 127.0.0.1 --port 8083
```

Tests use stub/fake transports, with no live LLM calls, API key, or Docker. CI uses Python 3.12, runs
lint and pytest, and installs a built wheel outside the source tree to check that prompt resources
ship with the non-editable installation used by Docker.

The tests exercise the Groq HTTP request and usage parsing, bounded status/validation retries,
deadline cancellation (including `Retry-After` waits), safe errors, startup validation, and provider
cleanup. They also cover citation grounding, escaped prompt injection attempts, concurrent budget
admission, UTC budget resets, and the service's existing health/auth endpoints.

With the local server running, send a stub assessment from another PowerShell terminal:

```powershell
$assessment = @{
  finding_id = 'demo-1'
  advisory_text = 'Demo advisory: package example-lib versions before 2.0.0 are affected.'
  service_context = 'Fictional demo service uses example-lib 1.0.0.'
} | ConvertTo-Json
$headers = @{ 'X-Service-Token' = 'dev-internal-token-change-me' }
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8083/v1/assess' `
  -Headers $headers -ContentType 'application/json' -Body $assessment
```

Use your configured service token if it differs. The default stub returns a deterministic,
schema-valid result, not a real security judgment.

## Enable Groq and smoke-test manually

For Compose, set these values in the ignored **repository-root `.env`** using your own key:

```dotenv
LLM_PROVIDER=groq
GROQ_API_KEY=<your-own-key>
GROQ_MODEL=openai/gpt-oss-120b
```

Then run `docker compose up --build` from the repository root. The AI container remains internal;
there is no new browser route, published port, or automatic assessment pipeline.

For a local manual API check, place those settings in `backend/ai-service/.env` instead, restart the
local Uvicorn server, and send the example assessment above. Verify the response has the documented
fields and each citation equals an input passage. You can also send
`{"finding_id":"demo-1","draft_type":"risk_ticket","context":"Fictional demo evidence."}` to
`/v1/draft`. Use only public or fictional material for this smoke test and avoid saving real-key
outputs in the repository.

Automated fake-transport tests do not establish that a particular account has access to the chosen
model or enough quota. A real-key smoke test must be performed separately; none is claimed here.

Provider references: [Groq structured outputs](https://console.groq.com/docs/structured-outputs)
and [reasoning parameters](https://console.groq.com/docs/reasoning). Other compatible servers or
models must accept this adapter's structured-output and reasoning fields.

## Draft quality evaluation (E8 story 9 → E14)

The standalone harness evaluates the AI narratives for risk tickets, upgrade rationales and
regulator memos. It uses 20 versioned synthetic golden cases: direct/transitive vulnerabilities,
vendor outages, Maven/npm/PyPI upgrades, missing fixes/context, multiple affected services,
long evidence, redaction and injection. It calls the same guarded draft capability and structured
generator as the API, with no database, core workflow action, or running stack required.

The [rubric](src/ai_service/evaluation/rubric.md) defines weighted format, grounding, citations,
actionability, uncertainty and safety criteria. Automated anchor checks produce quality **proxies**,
with critical-failure gates and separate human review. They cannot prove factual/semantic quality.
Core's final document templates, version selection and approval/execution remain covered by core tests.

After installing `pip install -e '.[dev]'`, run from `backend/ai-service`:

```bash
# Offline reference replay: validates the harness/pipeline, not live-model quality
python -m ai_service.evaluation --provider golden --output evaluation-results/golden

# The generic development stub should fail the quality rubric (exit 1), while attacks stay blocked
python -m ai_service.evaluation --provider stub --output evaluation-results/stub

# Compare the same corpus/rubric/selection; regression or failed case exits 1
python -m ai_service.evaluation --output evaluation-results/next --baseline evaluation-results/golden/report.json

# Explicit paid-provider evaluation only; use your process environment's GROQ_API_KEY
python -m ai_service.evaluation --provider groq --case upgrade-maven --output evaluation-results/groq-maven
```

On Windows use `.\.venv\Scripts\python.exe` in place of `python`. `--case` can be repeated;
`--model` overrides the Groq model; `--corpus` loads a validated alternative corpus;
`--max-score-drop` allows a chosen numeric decline from the baseline (a pass-to-fail regression
still fails); `--delay-seconds` paces multi-case live runs. Default mode is always offline golden
replay, even if `LLM_PROVIDER=groq` is configured. Live runs retain configured token/rate/daily
budgets; rate limits and provider failures are recorded separately and fail the affected case.
No live model run or API-key availability is implied by offline checks.

Each output folder contains `report.json` (versioned E14 input), `report.md` (context, candidate,
diagnostics), and `human-review.json` (null scores pending a reviewer). Reports include corpus,
rubric/scorer, prompt and guardrail fingerprints; provider/model/mode; outcomes; per-case dimensions;
latency and known token usage; aggregate scores and baseline regressions. Expected blocked cases
have a separate pass rate and are excluded from quality averages. `mean_auto_score` counts failed
quality generations as zero; `mean_generated_auto_score` covers produced drafts only, alongside
`generation_success_rate`, so availability is visible separately from narrative quality.

Exit codes: **0** proxy checks passed; **1** failed cases or regressions; **2** invalid corpus,
selection, provider configuration or incompatible baseline. Passing automated checks leaves
human review pending and never approves a response. Complete the separate review file using the
rubric and retain it with the reports for E14. This story provides that handoff, not the full E14
benchmark/reporting application. CI runs offline replay, uploads its reports, and checks packaged
evaluation resources. Local `evaluation-results/` is ignored; do not commit keys or real estate data.

## Layout

```text
src/ai_service/
  main.py           app lifecycle and wiring
  schemas.py        unchanged wire shapes mirrored by core
  core/             configuration, service-token auth, errors, observability
  api/              thin request routes
  capabilities/     assessment/drafting, internal outputs, citation validation
  prompts/          versioned templates and prompt construction
  llm/              provider port/adapters, structured generation, limits
  evaluation/       versioned golden cases, rubric, standalone CLI and E14 report exports
```

Dependencies flow `api → capabilities → llm`, with shared plumbing in `core` and wire models in
`schemas`. There is no database or vector store in this service.
