# Prometheus

Metrics for Aegis's identity service. Prometheus scrapes the service's Micrometer
metrics (Spring Boot Actuator) and lets you query them with **PromQL**. This folder holds
the scrape config ([`prometheus.yml`](prometheus.yml)); everything below is how to query it.

## How it's wired

Brought up by the repo's root `docker-compose.yml`:

| | |
|---|---|
| Prometheus UI | http://localhost:9090 |
| Scrape target | `backend:8081` (the identity service) |
| Metrics path | `/actuator/prometheus` |
| Scrape interval | 15s |
| Job name | `identity-service` |
| Config file | [`ops/prometheus.yml`](prometheus.yml) (mounted read-only into the container) |

Confirm the target is being scraped at http://localhost:9090/targets — it should be **UP**.

## Three ways to query

**1. Prometheus UI (PromQL).** Open http://localhost:9090 → **Graph** tab → enter an
expression → **Execute**. *Table* shows the current value; *Graph* shows it over time.

**2. HTTP API (JSON, scriptable).** Same PromQL, returns JSON:

```bash
curl -s 'http://localhost:9090/api/v1/query' --data-urlencode 'query=identity_audit_events_total' | jq .
```

`/api/v1/query` is an instant value; `/api/v1/query_range` (with `start`/`end`/`step`) returns a time series.

**3. Raw metrics (no Prometheus).** See exactly what the app publishes:

```bash
curl -s http://localhost:8081/actuator/prometheus | grep identity_
```

## Metric names & labels

Micrometer names become Prometheus names by **dots → underscores**, and counters get a
**`_total`** suffix — so `identity.audit.events` (in code) is queried as
`identity_audit_events_total`. Every app metric carries `application="identity-service"`, and
the scrape adds `job="identity-service"` and `instance="backend:8081"`.

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

## Notes

- **Counters need traffic.** `identity_*_total` and `http_server_requests_*` stay at zero until
  the matching action happens (log in, trip the rate limiter, hit an endpoint). Exercise the app
  first, then query.
- **Percentile latency** (`histogram_quantile(0.95, …_bucket)`) needs percentile histograms
  enabled — set `management.metrics.distribution.percentiles-histogram.http.server.requests=true`.
  Without it, use the average-latency form above.
- `rate()`/`increase()` windows should be at least ~4× the 15s scrape interval (≥1m); the examples
  use `5m`.
