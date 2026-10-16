# Aegis AI Service

The internal Python / FastAPI service for **assessment and response drafting**. Requests carry a
shared `X-Service-Token`, not a user JWT. Compose gives this service no host port and no gateway
route; only `aegis-core` shares its Docker network. On AWS, its security group admits only aegis-core.

**Status: built, and used by aegis-core's responses.** aegis-core's `HttpAiClient` calls `/v1/draft`
for an upgrade pull request's rationale (`upgrade`), a risk ticket's narrative (`risk_ticket`) and a
DORA memo's sections (`memo`), and `/v1/assess/look-for` then `/v1/assess` when a person asks whether a
service's code uses a vulnerable package's vulnerable function, feature or condition (E11-5). The
service supports an offline stub and a Groq provider, versioned prompts, strict output validation,
source-resolved citations, and local rate/token controls. The default remains `stub`, so development
and tests need no API key. There is no RAG yet: the service is stateless. It returns only proposed
text: aegis-core builds each document around it, falls back to its own text when this service fails,
and a person approves before anything is filed or opened.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/actuator/health` | Public | Liveness |
| GET | `/actuator/prometheus` | Public | HTTP and LLM metrics |
| GET | `/v1/ping` | `X-Service-Token` | Connectivity check, called by aegis-core's `/api/ai/ping` |
| POST | `/v1/assess/look-for` | `X-Service-Token` | `{finding_id, advisory_text, package: {ecosystem, name}, fix_summary?}` → `{terms}` |
| POST | `/v1/assess` | `X-Service-Token` | `{finding_id, advisory_text, package, fix_summary?, searches: [{term, files_searched, files_matched, lines_matched, complete}], excerpts: [{id, path, start_line, text, kind, term?}]}` → `{verdict, rationale, checks, cited_excerpts}` |
| POST | `/v1/draft` | `X-Service-Token` | `{finding_id, draft_type, context?}` → `{draft_type, content, ticket?, memo?, citations}` |

`finding_id` is an opaque string; aegis-core sends the id of the proposal, ticket or memo it is
drafting, or the service and change an assessment is for. Draft types are `risk_ticket`, `upgrade`, and `memo`; other values return 400. Snake-case
fields and response shapes continue to mirror core's `AiDtos`. FastAPI serves the OpenAPI
specification at `/openapi.json` and Swagger UI at `/docs`.

Each draft type has its own output. An `upgrade` draft is free text (`content`). A `risk_ticket` also
returns `ticket` (`summary`, `risk`, `impact`, `actions`), and a `memo` returns `memo` (`summary`,
`impact`, `measures`, `next_steps`). aegis-core assembles those documents itself: every fact,
priority and deadline around the sections is its own, and the model writes only the narrative.

An assessment is two calls, with aegis-core's search of the service's code between them; it is an AI
suggestion from targeted excerpts, not call-graph reachability.

- **Pass 1, `/v1/assess/look-for`:** the advisory (≤ 2,500 characters), the package and what the advisory's
  fix commit changed (`fix_summary`, ≤ 1,200: each changed source file and the functions its diff names)
  → up to five `terms` to search the code for. A term is kept only if one of those sources writes it as a
  whole name, it is 4–64 characters of `[A-Za-z0-9_$.:/@-]`, it isn't the package's own name, and it names
  no secret (`password`, `secret`, `token`, `credential`, `api_key`, `private_key`…); anything else is
  dropped, not refused. The model labels each name `application` (what a service's code writes to reach
  the vulnerable part: an API, a type it binds, a setting) or `internal` (the package's own code — most
  of a fix commit's names); only application names are returned, because a text search of a service's
  code can never find the package's internals. A name may also join words the advisory writes as prose
  (`cacheComponents` from "Cache Component"). Its output cap is 400 tokens.
- **Pass 2, `/v1/assess`:** the same advisory and fix summary, the search's findings per term (`searches`)
  and one to six code excerpts (`E1`–`E6`, ≤ 600 characters, `path` ≤ 300; `kind` `import`, `use` or
  `match`, a match naming its `term`). It answers `verdict` `CALLED`, `NOT_CALLED` or `CANT_TELL` (first,
  so the offline stub says it), a `rationale`, up to three `checks` (≤ 200 characters: what a reviewer
  should verify that the code shown can't, e.g. "Whether the server runs on Windows") and `cited_excerpts`.
  Passages: `A` advisory, `P1` package, `F1` the fix, `S1` the searches, `E*` the excerpts. The model first
  reads what triggers the vulnerability (a call, the shape of bound data, a setting or default, or the
  package's own server or framework code under a condition); `CALLED` means the excerpts show the
  trigger. `CALLED` must cite an excerpt; `NOT_CALLED` an excerpt or `S1` (a complete search for the
  names an application writes that found none — never the package's internals being absent, the whole
  package passed on, a missing setting when the advisory's vulnerable case is "without" it, or a
  remaining deployment condition such as the operating system); a trigger the code can't show is
  `CANT_TELL`, with checks. A check about a name a complete search found nowhere — written as the name or
  as its words — is dropped as settled; for Maven, a complete search finding no qualified type tells the
  model no class can use it. The model picks passage
  ids and the service resolves them, so an answer citing anything it wasn't sent is refused (502). Its
  output cap is 1,100 tokens.
- **Budget:** both passes, at their largest, fit the default 7,000 tokens a minute together (a test guards
  it), but one assessment uses most of a minute's budget: the next call within the minute gets a 429 with
  `Retry-After`, which aegis-core waits out once. Raise `LLM_MAX_TOKENS_PER_MINUTE` on a provider tier
  that allows more.

The answer is a suggestion: aegis-core shows it beside the score and never lets it change the score or
decide anything. Offline, the stub names no terms and no checks and always answers `CANT_TELL`.

## Provider, prompts, and validation

Capabilities depend on a structured-generation helper and the `LlmClient` port. Groq is an HTTP
adapter using the existing `httpx` dependency; no Groq SDK or optional package extra is required.
The `anthropic` provider remains explicitly unimplemented, including when the reserved `llm` extra
is installed. Unknown providers or Groq without a key fail at startup.

Prompts are package resources, loaded with `importlib.resources` and identified as `assess.v6`, `look_for.v2`,
`draft.v1` (the upgrade rationale), `draft_risk_ticket.v1` and `draft_memo.v1`. Untrusted advisory
and service text is placed in numbered, escaped data blocks; each code excerpt is its own passage,
headed by its path and line range. Embedded delimiters cannot close those
blocks. Prompts instruct the model to treat their content as data, and never to perform matching,
final scoring, decisions, or execution. The risk-ticket and memo prompts present aegis-core's
computed facts as final and limit each section's length; the memo prompt also forbids classifying
an incident as major or stating a figure the context doesn't give.

The model returns an internal Pydantic schema with citation IDs. A schema transformer makes every
object closed (`additionalProperties: false`) and every property required for strict structured
output. Responses are validated locally too: malformed JSON, forbidden fields, an unknown verdict,
unknown citation IDs, a `CALLED` assessment citing no code excerpt and a `NOT_CALLED` one citing neither an
excerpt nor the search are rejected. Citations are
resolved from the redacted input snippets sent to the model, each at most 1,000 characters; the model
does not supply citation text. The public contract continues to return those snippets as strings.

There is one retry for invalid model output and at most two status retries for 429/5xx responses.
Backoff honors `Retry-After` within **one overall deadline of at most 20 seconds**, shared by HTTP
attempts, validation retries, and retry waits. Other 4xx responses and timeouts are not retried.
Errors use the existing `{timestamp, status, error, message, path}` shape:

- **400:** invalid request or oversized input; text is never silently truncated.
- **400:** recognisable source control instructions; no provider call and no source text in the error.
- **502:** invalid provider output after the validation retry.
- **429:** a local per-minute limit (requests or tokens) is full now, with `Retry-After` — the seconds
  until enough of the rolling minute frees for this call. aegis-core waits that long (up to a minute)
  and asks once more.
- **503:** the daily budget exhausted, a call larger than a whole minute's budget, a timeout, or provider
  unavailability.

Logs contain operational metadata, including prompt version, rather than prompts, keys, generated
text, or raw provider error bodies. Metrics record requests by provider/model/capability/outcome,
token usage by direction, latency, and prompt versions. The Compose Prometheus container is on a
different network and does not scrape ai-service; the metrics endpoint can be inspected locally.

### Guardrails (E8 story 8)

`capabilities/_guardrails.py` screens advisory and service context for recognisable instruction
attacks, including role/control delimiters, requests to ignore prior rules, reveal secrets/prompts,
or bypass approval. Only instructions are screened, text that starts a sentence or addresses the
model ("your"): an advisory describing what an attacker can do ("allows an attacker to expose
credentials", "lets members bypass the approval rules") is drafted as usual. Screening normalises
Unicode compatibility characters and invisible format
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
| `LLM_MAX_OUTPUT_TOKENS` | `1500` | Maximum completion tokens per attempt (room for a memo's four sections) |
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
  advisory_text = 'Demo advisory: example-lib before 2.0.0 runs commands through render().'
  package = @{ ecosystem = 'npm'; name = 'example-lib' }
  excerpts = @(@{ id = 'E1'; path = 'src/app.ts'; start_line = 1; kind = 'use'
    text = "import { parse } from 'example-lib';`nexport const read = (s) => parse(s);" })
} | ConvertTo-Json -Depth 4
$headers = @{ 'X-Service-Token' = 'dev-internal-token-change-me' }
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8083/v1/assess' `
  -Headers $headers -ContentType 'application/json' -Body $assessment
```

Use your configured service token if it differs. The default stub returns a deterministic,
schema-valid result (`CANT_TELL`, no checks, citing nothing), not a real security judgment.

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
fields, each draft citation equals an input passage, and each cited excerpt is one you sent. You can also send
`{"finding_id":"demo-1","draft_type":"risk_ticket","context":"Fictional demo evidence."}` to
`/v1/draft`. Use only public or fictional material for this smoke test and avoid saving real-key
outputs in the repository.

Automated fake-transport tests do not establish that a particular account has access to the chosen
model or enough quota. A real-key smoke test must be performed separately; none is claimed here.

Provider references: [Groq structured outputs](https://console.groq.com/docs/structured-outputs)
and [reasoning parameters](https://console.groq.com/docs/reasoning). Other compatible servers or
models must accept this adapter's structured-output and reasoning fields.

## Draft and assessment quality evaluation (E8 story 9, E11-5 → E14)

The standalone harness evaluates the AI narratives for risk tickets, upgrade rationales and
regulator memos, and its reachability assessments. It uses 33 versioned synthetic golden cases:
20 drafts (direct/transitive vulnerabilities, vendor outages, Maven/npm/PyPI upgrades, missing
fixes/context, multiple affected services, long evidence, redaction and injection), 8 assessments
(`capability: "assess"`: called, not called, not called on a search that found nothing, can't tell,
a condition code can't show with what to check, a data-shape trigger absent and present, and an
instruction hidden in a code comment, which must be refused) and 5 look-for cases
(`capability: "look_for"`: a named function, the type an application binds kept while the fix's
internal class is dropped, a framework setting kept while its internals are dropped, a secret-ish
name dropped, an instruction in the fix summary refused). It calls the same
guarded capabilities and structured generator as the API, with no database, core workflow action, or
running stack required. An assessment is graded on its verdict (a wrong one is a critical failure),
on citing the excerpts it should and only those it was sent, and on its checks; a look-for answer on
naming the expected terms and none it must never keep.

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
