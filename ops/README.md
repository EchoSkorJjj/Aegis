# Operations monitoring

Metrics for Aegis's backend services. Prometheus scrapes each service's Micrometer metrics
(Spring Boot Actuator) and lets you query them with **PromQL**. This folder holds the scrape
config, alert rules, and a provisioned Grafana dashboard (CS203-151/152).

## Local demo

Run `docker compose up --build -d` from the repository root. Grafana is at
http://localhost:3001/d/aegis-operations, in the **Aegis** folder. On a fresh Grafana volume, sign in with
its initial `admin` / `admin` account and set a new password when prompted. Sign-up and anonymous access
are disabled; the port binds to loopback. The Prometheus data source and **Aegis operations** dashboard
load automatically from [`grafana/provisioning`](grafana/provisioning) and
[`grafana/dashboards/aegis-operations.json`](grafana/dashboards/aegis-operations.json).

Open http://localhost:9090/targets to check the three backend targets, and
http://localhost:9090/alerts to see pending and firing alerts. The dashboard also graphs their states.
Prometheus evaluates the rules; Grafana visualizes the same metrics. Notification delivery (email,
chat, paging) requires an operator-configured Alertmanager and receiver; this stack does not send messages.

Exercise matching and scoring by opening an advisory's change-impact view, then draft a response or
regulator memo with an authorized account. Histogram series appear after the first corresponding
operation. No page visit is needed for the queue count. Allow about a minute for its first refresh,
plus the next scrape and rule evaluation. Disabled feeds have no series. An operation with no calls,
a missing queue observation, and idle HTTP traffic display no data rather than fabricated zero latency
or a zero error fraction.

The dashboard is managed in files: edit its JSON and provisioning YAML. Restart Grafana after changing
provisioning; it checks dashboard JSON every 30 seconds. After changing Prometheus configuration/rules,
run `docker compose restart prometheus`. Its data volume keeps 15 days of metrics and alert state;
Grafana's volume keeps account settings. Removing those volumes discards that history.

## Pipeline tracing (CS203-153)

Compose exports gateway, aegis-core and ai-service spans to Zipkin at http://localhost:9411.
The AI image installs its `tracing` extra, Compose enables `TRACING_ENABLED`, and Zipkin joins
both networks to accept AI telemetry. ai-service stays on `internal` with no host port or gateway
route. W3C `traceparent` carries the trace ID through the gateway, core's observed `RestClient`,
and FastAPI. AI providers are outside this trace; no service token or request body is captured.

Core adds spans named `aegis.ingest.poll`, `aegis.ingest.osv`, `aegis.ingest.normalize`,
`aegis.match.change`, `aegis.score.change`, `aegis.score.rollup`, and `aegis.draft.ticket|upgrade|memo`.
Stage logs record start/success/failure, UUID correlation fields and elapsed milliseconds. Errors
on these spans record only their class, so the tracing advice never exports exception messages.
UUIDs are trace tags and log fields, never Prometheus labels.

Gateway/core logs include `[traceId=... spanId=...]`. ai-service applies the same fields to all its
application logs using request-local context, including failed requests; its route/status log
replaces Uvicorn's default access log in the image. Startup/background logs outside a request
have empty IDs (`-` in Python). With tracing disabled or its optional SDK absent, AI logs still
validate and reuse an incoming W3C trace ID, or generate one locally; they export no spans.

**Ingestion and later requests have separate lifetimes.** A scheduled poll captures raw events;
a later normalization run writes the signal, and a person's request scores or drafts it. These
are separate traces. Search `rawEventId` to join `ingest.capture` and `ingest.record`, then search
`changeSignalId` to follow that normalized record into scoring/drafting. An advisory ID is a
candidate signal ID; when another feed becomes the representative of the same CVE, its signal
ID changes. Use the stored advisory's canonical ID to resolve that transition. There is no
long-lived span waiting for a human to request a draft.

### Local demonstration

1. Run `docker compose up --build -d`. Rebuild ai-service to include its tracing dependencies.
2. With an authorized analyst or service owner, open an advisory and draft a response. This makes
   a fresh gateway request that includes scoring and, when needed, AI assessment/drafting.
3. Run `docker compose logs --since 5m gateway aegis-core ai-service`. Copy the trace ID from an
   `aegis.draft.ticket` stage log; find the same ID in the AI `stage=ai.request` logs. A fallback
   draft may succeed while the AI's HTTP span reports a failure; the logs distinguish those outcomes.
4. In Zipkin, choose **Find a trace** and search that trace ID. The gateway/core HTTP spans,
   pipeline child spans and ai-service server spans belong to the same trace.
5. To exercise scheduled ingestion, enable/configure a feed and inspect `ingest.capture` and
   `ingest.record` logs. Their UUID fields connect the separate poll, normalization and draft traces.

Java sampling uses `TRACING_SAMPLE_RATE` (Compose's default is 1.0). The AI follows an incoming
parent's sampling decision. Keep AI tracing off outside the local demo unless an operator has
configured its collector and access controls; this change does not enable tracing in Terraform.

Tracing regression checks (run from each backend module):

```text
# backend/aegis-core (no Docker needed)
./mvnw -Dtest=PipelineTracingTest,PackageStructureArchTest,HttpAiClientTest test
# backend/ai-service
python -m pip install -e '.[dev,tracing]'
python -m pytest -q
python -m ruff check .
```

`PipelineTracingTest` checks nested span parenting, MDC restoration, safe error tags and outbound
W3C headers from the real AI client configuration. `test_observability.py` uses an in-memory
exporter to verify FastAPI parent extraction, exported/logged ID agreement, error correlation
and overlapping-request isolation. CI installs the tracing extra so this coverage runs there too.

References: [Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
and [OpenTelemetry FastAPI instrumentation](https://opentelemetry-python-contrib.readthedocs.io/en/latest/instrumentation/fastapi/fastapi.html).

## Pipeline metrics

All durations are seconds and include success and failure. Micrometer adds `class`, `method` and
`exception` (`none` on success); these are code-defined values, never response text or user identifiers.
Each timer publishes `_seconds_bucket`, `_seconds_count`, `_seconds_sum` and `_seconds_max`.

| Prometheus metric | Labels | Meaning |
|---|---|---|
| `aegis_ingestion_poll_records_total` | `source_id`, `result=stored\|duplicate` | Raw events observed, by feed |
| `aegis_ingestion_normalize_records_total` | `source_id`, `result=normalized\|dropped\|deferred` | Raw events consumed, rejected or left for retry |
| `aegis_match_duration_seconds_bucket` | `operation=change\|dependencies`, `le` | Change matching (`MatchingService.matchChange`) or batched estate matching (`VulnerableDependencies.find`) |
| `aegis_score_duration_seconds_bucket` | `operation=change\|rollup`, `le` | Whole change scoring or estate rollup (`ScoringService`) |
| `aegis_draft_duration_seconds_bucket` | `kind=ticket\|upgrade\|memo`, `le` | Whole draft operation, including AI calls, retries, deterministic fallback and persistence |
| `aegis_decision_queue_depth` | service/scrape labels only | All organizations' Review & approve entries |
| `aegis_decision_queue_last_refresh_timestamp_seconds` | service/scrape labels only | Last complete queue count, Unix seconds; 0 means unavailable |

Ingestion counters are registered at zero for every configured feed at startup. Normalization counts
measure raw-event outcomes, not newly discovered CVEs: updates and the same CVE from another feed still
count as processing. Existing record counters are retained, so consumers need no migration.

Stage timings overlap: scoring includes matching; a ticket includes its upgrade draft and any reachability
assessment. Do not add these durations. Draft histograms extend to five minutes to cover AI rate-limit
waits; the other timers use Micrometer's default buckets. These measure application operations, not
HTTP latency or each pure score calculation inside recommendations.

The queue counts a ticket once when the ticket or an attached pull request needs attention (proposed,
filing/opening or failed); it adds pending **standalone** upgrade proposals and proposed regulator memos.
Filed/approved/dismissed responses without pending work are excluded. Findings with no drafted response
are outside Review & approve and outside this gauge. Counts use org-scoped stores and RLS without
loading response bodies. A count is a sample over one pass, not a cross-organization transaction.

`AEGIS_METRICS_DECISION_QUEUE_ENABLED` defaults to `false`; Compose sets it to `true`. It needs
`IDENTITY_SERVICE_TOKEN` matching identity-service's `IDENTITY_INTERNAL_SERVICE_TOKEN`, just like owner
notifications and posture snapshots. `AEGIS_METRICS_DECISION_QUEUE_INTERVAL` defaults to `PT1M`; keep it
at most one minute with the supplied three-minute freshness threshold. Only the `SchedulerLease` holder
refreshes; others publish unknown (`NaN`, timestamp 0). A failed pass publishes unknown, never a partial
total. No database queries run during a scrape. The recording rule `aegis:decision_queue_depth` takes
the maximum fresh replica value to avoid double counting at lease handover. No organization, service,
owner, advisory, repository or response identifiers are labels.

```promql
# p95 matching latency by entry point
histogram_quantile(0.95, sum by (le, operation) (rate(aegis_match_duration_seconds_bucket[5m])))

# p95 scoring latency
histogram_quantile(0.95, sum by (le, operation) (rate(aegis_score_duration_seconds_bucket[5m])))

# p95 drafting latency by draft type
histogram_quantile(0.95, sum by (le, kind) (rate(aegis_draft_duration_seconds_bucket[5m])))

# Fresh global decision queue observation
aegis:decision_queue_depth

# Per-backend-service 5xx fraction, excluding Actuator requests
aegis:http_5xx:ratio5m
```

## Alert runbook

Rules live in [`alerts.yml`](alerts.yml), evaluated once a minute. A condition must remain true for
the full `for` duration; recovery resets its pending time. Evaluation can add up to one minute.

| Alert | Condition and hold time | First checks |
|---|---|---|
| `AegisFeedDown` | Configured feed health is 0 for **45m** | Feed health/last success, poll failures, core logs, publisher connectivity, API limits and credentials |
| `AegisDecisionBacklogGrowing` | Queue grew in **each half** of the last hour, held for **5m**, with a complete hour of fresh observations | Review & approve, owner assignments, failed Jira filings and PR openings; queue refresh freshness |
| `AegisHighErrorRate` | Service's 5-minute HTTP 5xx fraction is strictly **>1% for 10m** | Service logs, request volume, Zipkin http://localhost:9411, downstream health. Do not blindly replay approved external actions |
| `AegisServiceDown` | Backend scrape fails for **5m** | Container health, networking, target errors. Covers a down core that cannot emit feed health |
| `AegisDecisionQueueMetricsStale` | No enabled replica refreshed within **3m**, for **5m** | Queue metrics switch, scheduler lease, identity internal token/API, database and core logs |

The backlog rule asks whether the queue is larger now than 30 minutes ago, **and** larger then than an
hour ago, so drafts ten or thirty minutes apart still alert while one draft awaiting a decision (or a
burst of drafts at once) does not, and a shrinking queue clears it. It requires 60 fresh minute
observations, then a five-minute hold; the first possible firing after a new series is about 65 minutes.
A gap in fresh history restarts the full-hour observation requirement. A large but flat queue is not
growth and does not trigger this alert.
The 5xx denominator excludes `/actuator.*` so monitoring requests cannot dilute real failures. It sums
instances **within each job**. Exactly 1%, idle traffic and 4xx do not trigger it.

A feed with no successful poll starts unhealthy. A stopped poller becomes unhealthy after the existing
`AEGIS_INGESTION_HEALTH_STALE_AFTER` (default 45m), then must stay unhealthy for another 45m to fire;
an explicit failed poll starts that hold immediately. OSV reports its own bulk-sync outcomes and
allows its configured daily poll interval **plus** the staleness grace before becoming stale
(24h 45m by default), so successful daily syncs stay healthy between polls.
It rechecks for the scheduler lease on the lease-renewal cadence (30s by default), so a delayed
lease acquisition at startup does not postpone its first sync for a day; downloads still run daily.
A failed OSV sync is retried after `AEGIS_OSV_RETRY_DELAY` (15m by default), then 30m, 60m and so on,
never longer than its poll interval: when the first retry succeeds, OSV is healthy again before
`AegisFeedDown`'s 45-minute hold ends; an outage that outlasts it fires the alert as for any feed.
Disabled connectors are absent, not unhealthy.

### Validation

From the repository root (PowerShell and POSIX shells both accept these single-line commands):

```text
docker compose -f docker-compose.yml config --quiet
docker run --rm --entrypoint promtool -v "./ops:/etc/prometheus:ro" prom/prometheus:latest check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint promtool -v "./ops:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:latest test rules alerts.test.yml
```

[`alerts.test.yml`](alerts.test.yml) checks exact hold times, resets, recovery, threshold equality,
zero traffic, scrape failure, stale queue data and replica deduplication using synthetic time.
CI runs these checks and parses dashboard JSON in
[`observability-ci.yml`](../.github/workflows/observability-ci.yml). Core's `mvn verify` covers real
histogram export, queue refresh/scope/failure behavior and persistence counts.

Reference: [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)
and [Prometheus rule testing](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/).

## How it's wired

Brought up by the repo's root `docker-compose.yml`:

| Job | Scrape target | Metrics path |
|---|---|---|
| `identity-service` | `identity-service:8081` | `/actuator/prometheus` |
| `aegis-core` | `aegis-core:8082` | `/actuator/prometheus` |
| `gateway` | `gateway:8080` | `/actuator/prometheus` |

| | |
|---|---|
| Prometheus UI | http://localhost:9090 |
| Scrape interval | 15s |
| Config file | [`ops/prometheus.yml`](prometheus.yml) (mounted read-only into the container) |

Confirm every target is being scraped at http://localhost:9090/targets — each should be **UP**.
(`ai-service` is intentionally not scraped — it's isolated to the `internal` Docker network.)

## Three ways to query

**1. Prometheus UI (PromQL).** Open http://localhost:9090 → **Graph** tab → enter an
expression → **Execute**. *Table* shows the current value; *Graph* shows it over time.

**2. HTTP API (JSON, scriptable).** Same PromQL, returns JSON:

```bash
curl -s 'http://localhost:9090/api/v1/query' --data-urlencode 'query=identity_audit_events_total' | jq .
```

`/api/v1/query` is an instant value; `/api/v1/query_range` (with `start`/`end`/`step`) returns a time series.

**3. Raw metrics (no Prometheus).** See exactly what an app publishes (each service exposes its
own `/actuator/prometheus` — identity-service on `:8081`, aegis-core on `:8082`):

```bash
curl -s http://localhost:8081/actuator/prometheus | grep identity_
curl -s http://localhost:8082/actuator/prometheus | grep aegis_ingestion
```

## Metric names & labels

Micrometer names become Prometheus names by **dots → underscores**, and counters get a
**`_total`** suffix — so `identity.audit.events` (in code) is queried as
`identity_audit_events_total`. Every metric carries `application="<service-name>"` (e.g.
`identity-service` or `aegis-core`), and the scrape adds `job` and `instance` per the table above.

---

## Query cookbook

### Scrape health

```promql
# Is the identity service being scraped? (1 = up, 0 = down)
up{job="identity-service"}

# How long the last scrape took
scrape_duration_seconds{job="identity-service"}
```

### Authentication & security (custom metrics)

Emitted by `AuditService` (`identity_audit_events_total`, labels `type` + `outcome`) and the
per-IP rate limiter (`identity_ratelimit_blocked_total`).

```promql
# All audit events, broken down by type and outcome
identity_audit_events_total

# Failed-login rate (per second, averaged over 5m)
sum(rate(identity_audit_events_total{type="LOGIN_FAILED"}[5m]))

# Account lockouts in the last hour
increase(identity_audit_events_total{type="LOGIN_LOCKED_OUT"}[1h])

# MFA outcomes (challenge issued / succeeded / failed)
sum by (type) (rate(identity_audit_events_total{type=~"MFA_.*"}[5m]))

# Successful vs failed events overall
sum by (outcome) (rate(identity_audit_events_total[5m]))

# Requests rejected by the per-IP rate limiter (per second)
rate(identity_ratelimit_blocked_total[5m])
```

### Ingestion feed health (aegis-core, CS203-47)

Emitted by `IngestionFeedMetrics`, one series per feed (`source_id` = `nvd`, `osv`, `vendor-status`, or
`security-news` today — one per registered `IngestionSource`). Registered eagerly at startup, so
every feed reports even before its first poll.

```promql
# Any unhealthy feed right now (never succeeded, last attempt failed, or stale — see below)
aegis_ingestion_health == 0

# Seconds since each feed's last successful poll (querying a gauge already in epoch seconds)
time() - aegis_ingestion_last_success_timestamp_seconds

# Poll attempts per feed, by outcome (per second, 5m average)
sum by (source_id, outcome) (rate(aegis_ingestion_poll_total[5m]))

# Raw events stored vs. deduplicated per feed poll
sum by (source_id, result) (rate(aegis_ingestion_poll_records_total[5m]))

# Records normalised vs. dropped vs. deferred per feed
sum by (source_id, result) (rate(aegis_ingestion_normalize_records_total[5m]))

# Total records a feed has ever successfully normalised (a running "how many records processed")
aegis_ingestion_normalize_records_total{result="normalized"}
```

A feed is unhealthy (`aegis_ingestion_health` = 0) if it has never had a successful poll, its most
recent poll attempt failed, or it's been longer than `aegis.ingestion.health.stale-after` (default
45 minutes — override via `AEGIS_INGESTION_HEALTH_STALE_AFTER`, an ISO-8601 duration) since its
last success. Self-scheduled OSV adds its own poll interval to that grace period. The gauge is sampled
fresh on every scrape, so it reflects staleness even between poll cycles, not just at the moment a poll runs.

### HTTP traffic & latency

Standard Spring Boot timer `http_server_requests_seconds` (labels `method`, `uri`, `status`, `outcome`).

```promql
# Request rate by endpoint
sum by (uri) (rate(http_server_requests_seconds_count[5m]))

# 5xx error rate
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

# 4xx rate by endpoint (e.g. spotting 401/403 storms)
sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"4.."}[5m]))

# Average latency by endpoint (seconds)
  sum by (uri) (rate(http_server_requests_seconds_sum[5m]))
/ sum by (uri) (rate(http_server_requests_seconds_count[5m]))
```

### JVM & runtime

```promql
# Heap memory in use
jvm_memory_used_bytes{area="heap"}

# Process CPU usage (0–1)
process_cpu_usage

# Open DB connections (HikariCP)
hikaricp_connections_active

# Live threads
jvm_threads_live_threads
```

---

## Label reference

**`identity_audit_events_total`** — `type` is an `AuditEventType`, `outcome` is `SUCCESS` or `FAILURE`.

Common `type` values:

`USER_REGISTERED` · `LOGIN_SUCCEEDED` · `LOGIN_FAILED` · `LOGIN_LOCKED_OUT` ·
`MFA_CHALLENGE_ISSUED` · `MFA_SUCCEEDED` · `MFA_FAILED` · `TOKEN_REFRESHED` · `LOGOUT` ·
`LOGOUT_ALL` · `EMAIL_VERIFICATION_SENT` · `EMAIL_VERIFIED` · `PASSWORD_RESET_REQUESTED` ·
`PASSWORD_RESET_COMPLETED` · `PASSWORD_CHANGED`

(The `AuditEventType` enum is the source of truth — check it for the full, current list.)

**`aegis_ingestion_*`** — all tagged `source_id` (the feed: `nvd`, `osv`, `vendor-status`,
`security-news`). `aegis_ingestion_poll_total`'s `outcome` is `success` or `failure`;
`aegis_ingestion_poll_records_total`'s `result` is `stored` or `duplicate` (raw events observed
while polling); `aegis_ingestion_normalize_records_total`'s `result` is `normalized`, `dropped`, or
`deferred` (raw events turned into domain records, permanently rejected, or queued for retry).
`aegis_ingestion_health` and `aegis_ingestion_last_success_timestamp_seconds` carry only `source_id`.

## Notes

- **Counters need traffic.** `identity_*_total`, `aegis_ingestion_*_total`, and
  `http_server_requests_*` stay at zero until the matching action happens (log in, trip the rate
  limiter, hit an endpoint, run a poll cycle). Exercise the app first, then query. The two ingestion
  *gauges* (`aegis_ingestion_health`, `aegis_ingestion_last_success_timestamp_seconds`) are the
  exception — they and the ingestion counters are registered for every configured feed at startup.
- **HTTP percentile latency** (`histogram_quantile(0.95, …_bucket)`) needs HTTP histograms
  enabled — set `management.metrics.distribution.percentiles-histogram.http.server.requests=true`.
  Without it, use the average-latency form above. Pipeline histograms are already enabled in code.
- `rate()`/`increase()` windows should be at least ~4× the 15s scrape interval (≥1m); the examples
  use `5m`.
