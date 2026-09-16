# Aegis Core

The domain and pipeline service for Aegis (Java / Spring): the inventory & map of the estate,
advisory ingestion, matching, scoring, and the execution of approved actions. The AI steps
(**assess** and **draft**) live in a separate Python **ai-service**, reached through the `ai` port.
See the root [`ARCHITECTURE.md`](../../ARCHITECTURE.md), [`BUILD_PLAN.md`](../../BUILD_PLAN.md), and
[`docs/adr/0001-aegis-core-language.md`](../../docs/adr/0001-aegis-core-language.md).

> **Status:** the Spring Boot wiring, security, module seams, and AI port are in place. The inventory
> module persists its core entities and direct relationships, exposes authenticated CRUD APIs, and
> answers impact graph queries (component/vendor → impacted services + owners, including transitive
> component dependents); the ingestion framework (scheduled polling, dedup, raw-event store) runs the
> **NVD CVE connector**, which normalises CPE, CVSS, and affected product versions into advisories.
> Matching, scoring, and action workflows remain planned.

## Modules (a modular monolith, split by seams later)

| Package | Responsibility |
|---|---|
| `inventory` | Services, components, vendors, SBOM dependencies, ownership — the map |
| `ingestion` | Scheduled feed polling (OSV / NVD / vendor) → normalised advisories *(queue seam)* |
| `matching`  | Advisory package + version range → components → impacted services/owners → findings |
| `scoring`   | Deterministic impact score per service and owner, with a "why" breakdown |
| `actions`   | Execute human-approved responses (PR / ticket / memo), audited |
| `ai`        | **Port** to the Python ai-service: `AiAssessor` / `AiDrafter` + DTOs + `HttpAiClient` |

## The AI boundary

The AI logic is **not** in this service. `ai` holds only the seam: the domain depends on the
`AiAssessor` / `AiDrafter` interfaces; the one implementation (`HttpAiClient`) calls the internal
ai-service over REST with a **service token** (`X-Service-Token`), not a user JWT. Transport (REST
now, a queue later) and the LLM provider can change behind these interfaces without touching the
domain. See `ARCHITECTURE.md` for the topology.

## How it runs

- **Port** `8082` (`SERVER_PORT`).
- **Database** — shares one PostgreSQL instance with identity-service; owns the **`core`** schema.
  Plain relational store (no pgvector — RAG belongs to the ai-service). Flyway manages the schema.
- **Auth** — OAuth2 resource server; validates identity-service's RS256 JWTs via its JWKS
  (`IDENTITY_JWKS_URI`), maps the `roles` claim, and authorizes per route.
- **Observability** — `/actuator/health|info|prometheus`, traces to Zipkin (propagated to the ai-service).

### Key environment variables

| Var | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `…/identity`, `identity`, `identity` | Shared datasource |
| `IDENTITY_JWKS_URI` | `http://localhost:8081/oauth2/jwks` | identity-service's public keys |
| `AI_SERVICE_URI` | `http://localhost:8083` | The internal ai-service base URL |
| `AI_SERVICE_TOKEN` | `dev-internal-token-change-me` | Shared service token sent to the ai-service |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | SPA origins allowed in dev |
| `ZIPKIN_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Trace collector |
| `AEGIS_DEMO_SEED_ENABLED` | `false` | Load the demo estate at startup (dev/demo only) |
| `AEGIS_INGESTION_ENABLED` | `true` | Run the feed scheduler |
| `AEGIS_INGESTION_POLL_INTERVAL` | `PT15M` | Feed poll cadence (ISO-8601 duration) |
| `AEGIS_INGESTION_NVD_ENABLED` | `false` | Poll the NVD CVE API (Docker Compose sets `true`) |
| `AEGIS_NVD_API_KEY` | *(empty)* | Optional NVD key: raises the rate limit from 5 to 50 req/30s |
| `AEGIS_NVD_INITIAL_WINDOW` | `P2D` | How far back the very first poll reaches (NVD caps at 120 days) |
| `AEGIS_NVD_NORMALIZE_INTERVAL` | `PT1M` | Cadence of the raw-event → advisory drain |
| `AEGIS_NVD_BASE_URL` | NVD 2.0 API | Override the feed endpoint (used by tests and mirrors) |

## Build & test

```bash
# from backend/aegis-core
./mvnw verify           # unit tests + integration tests (Testcontainers needs Docker) + format check
./mvnw spotless:apply   # fix import order / whitespace
mvn spring-boot:run     # run locally (needs Postgres on :5432 and identity-service on :8081)
```

In Docker Compose it builds and starts alongside identity-service and the ai-service. API docs at
`http://localhost:8082/swagger-ui.html`.

## Inventory API

Inventory endpoints are rooted at `/api/inventory`. Paginated entity collections and direct
relationship collections default to name/ID ascending order. `SERVICE_OWNER`, `ANALYST`, and
`COMPLIANCE_ADMIN` may read inventory; `ANALYST` and `COMPLIANCE_ADMIN` may create, update, delete,
link, and unlink it. `PLATFORM_ADMIN` and `ORG_ADMIN` are user-management roles, not Aegis domain roles.

The map supports multiple owners per business service. Deleting an inventory entity cascades only
its relationship rows; the related services, components, vendors, and owners remain intact. The
component dependency API stores direct edges only; transitive reachability is resolved at query
time by the impact endpoints below.

### Impact graph queries

- `GET /api/inventory/components/{componentId}/impact` — every business service that uses the
  component, directly or transitively (i.e. the service uses some other component that depends on
  it, at any depth), each with its owners. Each entry reports `direct` (true only when the service
  uses the queried component itself) and `viaComponent` (the component the service actually
  depends on directly).
- `GET /api/inventory/vendors/{vendorId}/impact` — every business service that uses the vendor
  directly, with its owners. Vendors have no dependency graph of their own, so this is a direct
  join, not a traversal.

Both require the same read role as the rest of the inventory API (`SERVICE_OWNER`, `ANALYST`,
`COMPLIANCE_ADMIN`) and 404 when the component/vendor does not exist.

### SBOM import (CycloneDX)

`POST /api/inventory/services/{serviceId}/sbom` (`ANALYST`, `COMPLIANCE_ADMIN`) takes a CycloneDX JSON
SBOM (`application/json` or `application/vnd.cyclonedx+json`) and merges it into the map:

- Components are matched by (ecosystem, name, version), case-insensitively, or created. The ecosystem
  is the purl type and the name follows advisory-feed spelling (`group:artifact` for Maven,
  `@scope/name` for npm); components without a purl use the `generic` ecosystem. Components with no
  version are skipped; nested components are included.
- `dependencies` become component dependency edges. The service is linked only to its top-level
  components (the SBOM root's direct dependencies, plus any component nothing else depends on), so
  deeper libraries are reported as transitive impact.
- The import is additive, idempotent, and atomic — a malformed purl, oversized field, or more than
  5,000 components rejects the whole document with a 400. The response counts what changed.

### Demo estate

A fictional estate — five services, their owners and vendors, and four CycloneDX SBOMs (Maven, npm,
PyPI) — lives in `src/main/resources/demo/`. Set `AEGIS_DEMO_SEED_ENABLED=true` (Docker Compose does)
to load it at startup through the SBOM import; seeding is idempotent. Try the impact of
`org.apache.logging.log4j:log4j-core 2.14.1`: Order Service uses it directly, Payments API reaches it
through three hops.

## Ingestion

Scheduled polling of external feeds into an append-only, de-duplicated **raw-event store**
(`raw_events`), before any normalisation. The **NVD connector** is the first feed; OSV, vendor
status, and security news follow the same seam.

- **Seam** — a feed is an `IngestionSource` bean (`sourceId()` + `poll()`). `IngestionScheduler`
  discovers every such bean and polls it on `aegis.ingestion.poll-interval` (ISO-8601, default 15m),
  applying retry/backoff and isolating one feed's failure from the others. Add a connector by
  implementing `IngestionSource` as a `@Component` — nothing else is required.
- **Dedup** — `RawEventStore.saveIfNew` keys on `sourceId:externalId` (or `sourceId:sha256(payload)`
  when a feed has no id), enforced by a unique index, so overlapping re-polls are safe.
- **Retry/backoff** — each poll is retried with bounded exponential backoff (`aegis.ingestion.retry.*`).
- **Normalisation** — a connector only captures payloads; a separate drain turns unprocessed raw
  events into records and stamps `raw_events.processed_at`. Fetching and parsing therefore fail
  independently: a parser bug never loses data already captured — fix it, clear `processed_at`, and
  the events replay.
- Disable the scheduler with `AEGIS_INGESTION_ENABLED=false`.

> **Writing a connector?** Build it its own `RestClient` with explicit connect/read timeouts (see
> `NvdApiClient`). Do **not** inject the application's `RestClient` bean — that one points at the
> internal ai-service and carries its `X-Service-Token` as a default header, which would be sent to
> the external feed. A connector without a read timeout can also pin the shared ingestion thread.

### NVD connector (CVE API 2.0)

Polls [NVD](https://nvd.nist.gov/developers/vulnerabilities) for recently modified CVEs and
normalises each into an **advisory**: the primary CVSS metric plus the affected
(vendor, product, version-range) tuples that matching will resolve against registered components.

- **Windowed polling** — each poll asks for `lastModStartDate`..`lastModEndDate` only. The window
  starts at the newest `occurred_at` stored for the feed (which the connector sets to the CVE's
  `lastModified`) minus `overlap`, so the cursor lives in the store and a restart resumes from what
  was actually persisted. Before anything is stored the window is `initial-window` wide; anything
  wider than NVD's 120-day limit is clamped.
- **Revisions** — NVD rescores and re-scopes CVEs in place, so the external id is
  `<cve-id>@<lastModified>`. Each revision is a distinct raw event rather than a duplicate, the raw
  store keeps the full revision history, and the advisory row is updated in place.
- **CVSS** — the newest generation wins (v3.1 → v3.0 → v2), preferring NVD's `Primary` metric, so
  scoring gets one comparable number. A CVE still awaiting analysis simply has none.
- **CPE** — parsed escape-aware (`vendor\:name` keeps its literal colon); `*` and `-` mean "no
  concrete value". A match with no concrete vendor and product can identify nothing and is dropped,
  as are non-`vulnerable` matches and negated nodes. Version ranges win over a concrete CPE version
  when a match carries both.
- **Untrusted input** — payloads are size-capped (`max-payload-bytes`), every field is optional, a
  single CVE's affected list is bounded, and nothing here is fed to an LLM. An unparseable payload is
  logged and marked processed rather than blocking the queue; the raw row stays for inspection.
- **Rate limits** — NVD allows 5 requests per rolling 30s unauthenticated, 50 with a key. Set
  `AEGIS_NVD_API_KEY` (never commit one); `page-delay` keeps multi-page fetches inside the limit.

Turn the feed off on its own with `AEGIS_INGESTION_NVD_ENABLED=false` — the rest of the scheduler
keeps running. It is **off by default** so the test suite and a plain local run never reach out to
nvd.nist.gov.

### Advisories API

- `GET /api/advisories` — normalised advisories, most recently revised first, each with its affected
  products. Paged like the inventory collections.
- `GET /api/advisories/{sourceId}/{externalId}` — one advisory, e.g. `/api/advisories/nvd/CVE-2021-44228`.

Read-only: advisories are written by connectors, never by a user. Both need the same Aegis domain
read roles as the inventory API (`SERVICE_OWNER`, `ANALYST`, `COMPLIANCE_ADMIN`).

### Seeing it work

`docker compose up` enables the connector with a demo cadence (first poll ~1 minute after startup
over a one-day window, instead of the 15-minute/two-day defaults). Within about two minutes:

```bash
curl -s localhost:8080/api/advisories -H "Authorization: Bearer $TOKEN" | jq '.totalElements'
curl -s localhost:8080/api/advisories/nvd/CVE-2021-44228 -H "Authorization: Bearer $TOKEN" | jq
```

`$TOKEN` is an access token for a user holding a domain role — sign in through the frontend, or hit
`/auth/login` on the gateway. Progress is visible in `docker compose logs -f aegis-core` (`nvd: N
event(s)`, then `nvd: normalised N advisory/advisories`).
