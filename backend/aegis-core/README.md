# Aegis Core

The domain and pipeline service for Aegis (Java / Spring): the inventory & map of the estate,
advisory ingestion, matching, scoring, and the execution of approved actions. The AI steps
(**assess** and **draft**) live in a separate Python **ai-service**, reached through the `ai` port.
See the root [`ARCHITECTURE.md`](../../ARCHITECTURE.md), [`BUILD_PLAN.md`](../../BUILD_PLAN.md), and
[`docs/adr/0001-aegis-core-language.md`](../../docs/adr/0001-aegis-core-language.md).

> **Status:** the Spring Boot wiring, security, module seams, and AI port are in place. The inventory
> module persists its core entities and direct relationships, exposes authenticated CRUD APIs, and
> answers impact graph queries (component/vendor → impacted services + owners, including transitive
> component dependents); the ingestion framework (scheduled polling, dedup, raw-event store) is
> scaffolded with no feeds wired yet, and matching, scoring, and action workflows remain planned.

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
| `AEGIS_INGESTION_ENABLED` | `true` | Run the feed scheduler (no feeds are wired yet) |
| `AEGIS_INGESTION_POLL_INTERVAL` | `PT15M` | Feed poll cadence (ISO-8601 duration) |

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
link, and unlink it. `ADMIN` remains a platform user-management role, not an Aegis domain role.

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
(`raw_events`), before any normalisation. The framework is in place; the feed connectors and the
normalisation of raw events into `Change`/`Signal` records are later stories, so no feeds ship yet.

- **Seam** — a feed is an `IngestionSource` bean (`sourceId()` + `poll()`). `IngestionScheduler`
  discovers every such bean and polls it on `aegis.ingestion.poll-interval` (ISO-8601, default 15m),
  applying retry/backoff and isolating one feed's failure from the others. Add a connector by
  implementing `IngestionSource` as a `@Component` — nothing else is required.
- **Dedup** — `RawEventStore.saveIfNew` keys on `sourceId:externalId` (or `sourceId:sha256(payload)`
  when a feed has no id), enforced by a unique index, so overlapping re-polls are safe.
- **Retry/backoff** — each poll is retried with bounded exponential backoff (`aegis.ingestion.retry.*`).
- Disable the scheduler with `AEGIS_INGESTION_ENABLED=false`.
