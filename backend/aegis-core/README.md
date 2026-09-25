# Aegis Core

The domain and pipeline service for Aegis (Java / Spring): the inventory & map of the estate,
advisory ingestion, matching, scoring, and the execution of approved actions. The AI steps
(**assess** and **draft**) live in a separate Python **ai-service**, reached through the `ai` port.
See the root [`ARCHITECTURE.md`](../../ARCHITECTURE.md), [`BUILD_PLAN.md`](../../BUILD_PLAN.md), and
[`docs/adr/0001-aegis-architecture-and-multi-tenancy.md`](../../docs/adr/0001-aegis-architecture-and-multi-tenancy.md).

> **Status:** the Spring Boot wiring, security, module seams, and AI port are in place. The inventory
> module persists its core entities and direct relationships, exposes authenticated CRUD APIs, and
> answers impact graph queries (component/vendor → impacted services + owners, including transitive
> component dependents); the ingestion framework (scheduled polling, dedup, raw-event store) runs four
> connectors — the **NVD CVE feed** (CPE, CVSS and affected versions → advisories), **OSV**
> (ecosystem-native advisories keyed by purl; off by default), **vendor status
> pages** (Statuspage/RSS → vendor incidents), and **security news** (RSS/Atom → articles with the CVE
> identifiers they mention) — unified for reading through the **`change_signals`** view, with
> per-feed health/counts/last-success exposed as Prometheus metrics. **Deterministic matching is
> built:** advisory CPE/package + SemVer range matching, dependency and vendor traversal, and explicit
> evidence chains. **Impact scoring is built:** every impacted service is scored
> `severity x exposure x criticality`, banded into a tier and rolled up per owner and per service,
> with every weight in the model configurable and every score's contributing factors surfaced.
> Action workflows remain planned.

## Modules (a modular monolith, split by seams later)

Top-level packages are the seams; ArchUnit (`PackageStructureArchTest`) enforces the boundaries below —
persistence stays behind each module's `Store` port, the ingestion framework stays independent of its
connectors and the domains, `platform` depends on no feature, no controller lives under `ingestion`,
and the modules are cycle-free.

| Package | Responsibility |
|---|---|
| `platform`   | Cross-cutting foundation: the `ApiError` contract, security/OpenAPI config, tenancy (`CurrentOrg`), ping |
| `ingestion`  | The pipeline framework (scheduled poll → dedup → raw-event store → drain); source connectors under `ingestion.connectors` (NVD, OSV, vendor status, security news) |
| `advisories` | Normalised advisories (NVD/OSV) + read API |
| `incidents`  | Normalised vendor incidents (status pages) + read API |
| `news`       | Security-news articles with extracted CVE references + read API |
| `signals`    | The unified read-only `change_signals` view over advisories, incidents, and news |
| `inventory`  | Services and their repository links, components, vendors, SBOM dependencies, ownership — the map |
| `matching`   | Advisory package + version range → components → impacted services/owners + evidence chains |
| `scoring`    | Deterministic impact score per service and owner, with a "why" breakdown |
| `actions`    | Validated draft-PR types, provider registry and disabled GitHub App spike; approved execution workflows remain planned |
| `ai`         | **Port** to the Python ai-service: `AiAssessor` / `AiDrafter` + DTOs + `HttpAiClient` |

## Service repositories and draft PR providers (E8 Story 2)

`PUT /api/inventory/services/{serviceId}/repository` accepts `{"url":"https://github.com/acme/payments","branch":"develop"}`
and returns the updated service. `DELETE` on the same path unlinks it (204). Service responses include
nullable `repositoryUrl` and `repositoryBranch`; omitting the branch selects the repository default.
The governance service-update endpoint preserves these fields. Analysts, compliance admins and the
service's assigned owners can maintain the link; reads and writes remain scoped to the active org.
The service detail page shows the link and branch and prefills repository SBOM imports from them.

`RepositoryUrl` is shared by repository linking and SBOM generation. Both use
`aegis.inventory.sbom-generation.allowed-hosts`, even when SBOM generation is disabled. Only HTTPS
URLs are accepted; hosts are lower-cased and `.git`/trailing slash removed. Credentials, query strings,
fragments, custom ports, invalid path segments and unsafe branch/tag refs are rejected. Credentials
for private SBOM imports remain separate and are never persisted in the link.

Migration **V17** adds the nullable repository columns and branch/link constraint. Coordinate this
number with other branches before merging; never renumber a migration already applied to a shared DB.

`actions.pr.PrProvider` exposes only provider selection and opening a **draft** PR. `PrProviders`
discovers optional adapters; the default application has none and reports a conflict for unsupported
hosts. There is no execution endpoint or scheduler. The future approval flow must authorize the action
and target repository before calling the port; the validated draft is not proof of approval.

Drafts require a safe `aegis/` head distinct from an explicit base, a title up to 256 characters, a body
up to 60,000 characters, and 1–20 distinct file changes of at most 1 MiB UTF-8 each. Paths must be relative
and normalized; changes under `.github/workflows/` are refused. A provider resolving a null base to the
repository default must also check that the resolved base differs from the head.

## GitHub App spike (E8 Story 3)

The optional `actions.pr.github` adapter implements App JWT authentication, repository-scoped
installation tokens, branch/file commits and draft PR creation. It ships **disabled**; an empty account
allow-list refuses every operation. Tests use a local fake GitHub server and temporary RSA keys.
No real App is registered, and there is no execution endpoint. Production requires verified
org-scoped installation bindings and a human-approved execution/audit workflow.
See [GITHUB_APP.md](GITHUB_APP.md) for configuration, permissions, key handling, test evidence and the
future sandbox smoke procedure.

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
- **Observability** — `/actuator/health|info|prometheus`, traces to Zipkin (propagated to the
  ai-service), and per-ingestion-feed health/counts/last-success metrics (see "Ingestion feed
  health" below and `ops/README.md`).

### Key environment variables

| Var | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `…/identity`, `identity`, `identity` | Shared datasource |
| `IDENTITY_JWKS_URI` | `http://localhost:8081/oauth2/jwks` | identity-service's public keys |
| `IDENTITY_BASE_URL` | `http://localhost:8081` | identity-service's member directory — verifies a service's assigned owner, called with the caller's own token |
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
| `AEGIS_INGESTION_VENDOR_STATUS_ENABLED` | `false` | Poll vendor status pages (Docker Compose sets `true`) |
| `AEGIS_VENDOR_STATUS_NORMALIZE_INTERVAL` | `PT1M` | Cadence of the raw-event → vendor-incident drain |
| `AEGIS_INGESTION_NEWS_ENABLED` | `false` | Poll security news feeds (Docker Compose sets `true`) |
| `AEGIS_NEWS_NORMALIZE_INTERVAL` | `PT1M` | Cadence of the raw-event → news-item drain |
| `AEGIS_INGESTION_HEALTH_STALE_AFTER` | `PT45M` | How long since a feed's last success before `aegis_ingestion_health` reports unhealthy |

The vendor and news feed lists are YAML (`aegis.ingestion.vendor-status.vendors`,
`aegis.ingestion.news.feeds`), not env vars — each entry needs several fields.

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
relationship collections default to name/ID ascending order. Permissions follow the three lines of
defence (ADR §6d) — governance sets how risky a service is and who answers for it; the owner keeps
what it is made of accurate:

| Who | May |
|---|---|
| `SERVICE_OWNER`, `ANALYST`, `COMPLIANCE_ADMIN` | Read the whole inventory |
| `ANALYST`, `COMPLIANCE_ADMIN` (governance) | Register, edit, and delete services, components, and vendors; set a service's criticality and exposure; assign and unassign its owners; maintain any service's composition |
| A service's assigned owner (`SERVICE_OWNER`) | Maintain **that** service's composition — import or generate its SBOM, link and unlink its components and vendors — but not re-grade or re-staff it, and not anyone else's |

`PLATFORM_ADMIN` and `ORG_ADMIN` are user-management roles, not Aegis domain roles.

A business service carries the two attributes scoring multiplies in: its `criticality`
(`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`) and its **`exposure`** — `INTERNET_FACING`, `INTERNAL`, or
`ISOLATED`, i.e. whether the service talks to anything outside its own application. Neither is
derivable from the estate graph; both are business declarations, and because they set the score they
are governance's to make — an owner cannot lower their own service's risk. `exposure` is optional on
create (it defaults to `INTERNAL`) and omitting it on update leaves the current value alone, so a PUT
that predates the attribute cannot silently downgrade a service declared internet-facing.

### Owners

An owner is a real person — an organization member holding the `SERVICE_OWNER` role in
identity-service — never a free-text name. `POST /api/inventory/services/{serviceId}/owners` with
`{"userId": "…"}` (governance roles) assigns one: aegis-core first looks the member up in
identity-service **using the caller's own token**, so a user outside the caller's organization is
simply not found (404), and a member without `SERVICE_OWNER` or with a disabled account is refused
(400). A service may have several owners; `DELETE /api/inventory/services/{serviceId}/owners/{ownerId}`
removes one. Each owner record keeps a snapshot of the person's name and email, refreshed on every
assignment. Someone who no longer owns any service stops being an owner, so the read-only
`GET /api/inventory/owners` is exactly the people currently accountable for something.

Deleting an inventory entity cascades only its relationship rows; the related services, components,
and vendors remain intact. The component dependency API stores direct edges only; transitive
reachability is resolved at query time by the impact endpoints below.

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

`POST /api/inventory/services/{serviceId}/sbom` (`ANALYST`, `COMPLIANCE_ADMIN`, or the service's assigned
owner — as is `…/sbom/from-repo`) takes a CycloneDX JSON
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

A fictional estate — five services, their vendors, and four CycloneDX SBOMs (Maven, npm,
PyPI) — lives in `src/main/resources/demo/`. Set `AEGIS_DEMO_SEED_ENABLED=true` (Docker Compose does)
to load it at startup through the SBOM import; seeding is idempotent. Try the impact of
`org.apache.logging.log4j:log4j-core 2.14.1`: Order Service uses it directly, Payments API reaches it
through three hops. The seed assigns **no owners** — an owner is a verified organization member, and
the seed runs with no user to verify one against. To demo ownership, invite members with the Service
Owner role and assign them from each service's page.

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
- **Normalisation** — a connector only captures payloads; `RawEventDrainer` turns unprocessed raw
  events into records and stamps `raw_events.processed_at`. Fetching and parsing therefore fail
  independently: a parser bug never loses data already captured — fix it, clear `processed_at`, and
  the events replay. An event the handler cannot parse is still marked processed, so one bad payload
  never becomes a poison pill.
- Disable the scheduler with `AEGIS_INGESTION_ENABLED=false`.
- **Resilience is tested against mocked feeds, not live external calls** (CS203-47): every connector
  test spins up a real local HTTP server (`com.sun.net.httpserver.HttpServer`) or hand-built fixture,
  never nvd.nist.gov or a real status page. Covered: a feed returning an error status or being
  completely unreachable (`NvdSourceTest`, and the pre-existing per-vendor/per-publisher isolation in
  `VendorStatusSourceTest`/`NewsSourceTest`), a persistently failing poll exhausting retry/backoff
  (`IngestionRetryTest`), one failing source not blocking the others (`IngestionSchedulerTest`), and
  malformed/invalid payloads at both the parser level (`NvdAdvisoryParserTest`,
  `StatuspageFeedReaderTest`, `SyndicationFeedParserTest`) and end to end through the real drain path
  (`*NormalizerIntegrationTest.marksAnUnparseableDocumentProcessedRatherThanBlockingTheQueue`, and
  `RawEventDrainerTest` for the drain loop's own accounting) — a bad payload is dropped, never a
  poison pill that wedges the queue.

> **Writing a connector?** Use `FeedHttpClient` (or build your own `RestClient` with explicit
> connect/read timeouts, as `NvdApiClient` does). Do **not** inject the application's `RestClient`
> bean — that one points at the internal ai-service and carries its `X-Service-Token` as a default
> header, which would be sent to the external feed. A connector without a read timeout can also pin
> the shared ingestion thread. For RSS/Atom, parse through `SyndicationFeedParser` rather than rolling
> your own XML: it is hardened against XXE, and that hardening should exist in exactly one place.

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

### Vendor status connector (Statuspage / RSS)

Polls the status pages of the third parties the estate depends on and normalises each outage into a
**vendor incident**: who is impaired, what the vendor calls it, when it started, and whether it is
resolved. Matching will resolve `vendorName` against the inventory's registered vendors to reach the
affected services, the way advisories resolve through components.

- **Two formats** — `statuspage` reads the Atlassian Statuspage JSON API
  (`/api/v2/incidents.json`), which most hosted status pages speak and which carries a stable id, a
  lifecycle status, an impact level and a resolution time. `rss` reads an RSS 2.0 or Atom feed for
  vendors that publish no API (AWS, for one); those carry no status or impact, so both stay null
  rather than being invented.
- **Whole-document capture** — a status page has no window or cursor API, so each poll stores the
  document as one raw event keyed `<vendor-key>@<sha256 of the document>`. An unchanged page hashes
  to an event already stored and de-duplicates away; any edit is a new event. One raw event therefore
  normalises into many incidents.
- **Updated in place** — incidents are keyed `(source, <vendor-key>:<incident-id>)`, so a vendor
  moving an incident from *investigating* to *resolved* updates the same row rather than adding one.
- **Per-vendor isolation** — the framework already isolates one *feed's* failure from another's, but
  this connector speaks to many vendors, so it also catches per vendor: one status page being down,
  slow or malformed must not cost the others their poll.
- **Untrusted content** — documents are size-capped, and the XML reader is hardened against XXE
  (DOCTYPE declarations rejected, external entities never resolved, no entity expansion), so a
  hostile feed cannot read local files or reach internal hosts.

Configured vendors ship as the five the demo estate depends on — AWS (RSS), Cloudflare, Stripe,
Auth0 and Snowflake (Statuspage). Their `name` values match the inventory's vendor names on purpose.
Replace them per deployment. Turn the feed off on its own with
`AEGIS_INGESTION_VENDOR_STATUS_ENABLED=false`; like NVD it is **off by default** so tests and plain
local runs never reach third-party pages.

### Vendor incidents API

- `GET /api/vendor-incidents` — normalised incidents, most recently started first.
- `GET /api/vendor-incidents/{sourceId}/{externalId}` — one incident, e.g.
  `/api/vendor-incidents/vendor-status/cloudflare:qc2v4gxy3gfw`.

Read-only, and behind the same Aegis domain read roles as the inventory and advisory APIs.

### Security news connector (RSS / news feeds)

Polls security news and threat-intel publishers and normalises each article into a **news item**.

- **Credential-free feeds only** — an RSS or Atom URL, never a "news API" needing a key, because that
  would put a secret in configuration. Defaults ship as CISA advisories, The Hacker News,
  BleepingComputer, SANS ISC and Krebs on Security.
- **CVE extraction is the point.** An advisory names an affected package and an incident names an
  impaired vendor, but an article only tells a story. So the connector pulls the CVE identifiers out
  of each title and summary by plain pattern match — never by a model — giving matching something
  deterministic to join on: article → advisory → component → service. An article naming no CVE still
  lands; it simply carries no join.
- **Whole-document capture, per-feed isolation, size caps** — same shape as the vendor status
  connector, and the same hardened XML parser.
- **Prompt-injection posture.** News is the most attacker-influenceable content the platform ingests.
  Nothing here reaches an LLM; the only interpretation applied is the CVE regex, and everything else
  stays inert text. When the AI steps land, that guardrail lives at the ai-service boundary.

### News API

- `GET /api/news` — normalised articles, most recently published first.
- `GET /api/news?cve=CVE-2021-44228` — only articles mentioning that CVE (case-insensitive). This is
  the join matching will use.
- `GET /api/news/{sourceId}/{externalId}` — one article.

Read-only, behind the same Aegis domain read roles as the other domain APIs.

### Change signals API (unified view)

Advisories, vendor incidents, and news items are each written by their own connector, but matching,
scoring, and the frontend need to treat "a real-world change" uniformly. `change_signals` is a
read-only database view (`V10__change_signals_view.sql`) that `union all`s the three normalised
tables into one shape — it owns no data of its own; each source's connector remains the sole writer
of its own idempotently-upserted table.

- `GET /api/change-signals` — every signal from every source, most recently occurred first
  (`occurredAt` is each row's revision/update/publish timestamp, coalesced down to when it was first
  ingested).
- `GET /api/change-signals/{id}` — one signal by its id (the underlying advisory/incident/news row's
  own id).

Each response carries a `signalType` of `VULNERABILITY`, `VENDOR_OUTAGE`, or `SECURITY_NEWS` telling
you which table it was projected from. Read-only and behind the same Aegis domain read roles as the
per-source APIs above.

### Ingestion feed health (metrics, CS203-47)

Per-feed health, record counts, and last-success time — exposed as Micrometer metrics on the
existing `/actuator/prometheus` endpoint (no new REST surface; nothing here needs a JWT, since
`/actuator/prometheus` is already public — see `SecurityConfig`). `IngestionFeedMetrics` registers
one series per `IngestionSource` bean (`nvd`, `vendor-status`, `security-news` today) at startup:

- `aegis_ingestion_health{source_id}` — 1 (healthy) or 0. Unhealthy if the feed has never
  succeeded, its most recent poll attempt failed, or it's been longer than
  `aegis.ingestion.health.stale-after` (default 45m) since its last success.
- `aegis_ingestion_last_success_timestamp_seconds{source_id}` — epoch seconds of the last
  successful poll.
- `aegis_ingestion_poll_total{source_id,outcome}` and `aegis_ingestion_poll_records_total
  {source_id,result}` — poll attempts and raw events observed (`stored`/`duplicate`).
- `aegis_ingestion_normalize_records_total{source_id,result}` — records normalised, dropped, or
  deferred for retry.

See [`ops/README.md`](../../ops/README.md) for the full PromQL cookbook (finding unhealthy feeds,
staleness queries, per-feed throughput).

### Seeing it work

`docker compose up` enables all three connectors with a demo cadence (first poll ~1 minute after
startup; NVD over a one-day window, instead of the 15-minute/two-day defaults). Within about two
minutes:

```bash
curl -s localhost:8080/api/advisories -H "Authorization: Bearer $TOKEN" | jq '.totalElements'
```

```bash
curl -s localhost:8080/api/vendor-incidents -H "Authorization: Bearer $TOKEN" | jq '.content[0]'
```

```bash
curl -s "localhost:8080/api/news?cve=CVE-2021-44228" -H "Authorization: Bearer $TOKEN" | jq '.content[].title'
```

```bash
curl -s localhost:8080/api/change-signals -H "Authorization: Bearer $TOKEN" | jq '.content[] | {signalType, title}'
```

```bash
# Per-feed health/last-success/record-count metrics — no auth needed, direct to aegis-core.
curl -s localhost:8082/actuator/prometheus | grep ^aegis_ingestion
```

`$TOKEN` is an access token for a user holding a domain role (`SERVICE_OWNER`, `ANALYST` or
`COMPLIANCE_ADMIN`) — note that `PLATFORM_ADMIN` deliberately is not one. Progress shows in
`docker compose logs -f aegis-core`:

```
ingestion source 'nvd': 2286 new, 0 duplicate(s)
nvd: normalised 2286 record(s), 0 unparseable
ingestion source 'vendor-status': 5 new, 0 duplicate(s)
vendor-status: 238 incident(s) normalised
ingestion source 'security-news': 5 new, 0 duplicate(s)
security-news: 147 article(s) normalised
```

## Matching and evidence (Epic 6 stories 1–3)

`GET /api/matching/change-signals/{id}` contextualizes one vulnerability or vendor-outage signal
against the caller's active organization's current inventory. It requires `SERVICE_OWNER`, `ANALYST`,
or `COMPLIANCE_ADMIN`, like the inventory and feed APIs.

- Vulnerabilities match application CPE product identities to package names, then apply concrete or
  inclusive/exclusive SemVer bounds. Exact package matches are preferred; a shortened CPE product
  alias is accepted only when its vendor also appears in the package namespace. Unparseable range
  versions fail closed rather than creating a false positive.
- The reverse dependency graph is traversed breadth-first from each affected component. The response
  groups results by service and gives each reason the shortest component path from the vulnerable
  component to the component that service uses directly, followed by that service's owners. Cycles
  terminate and do not duplicate a service.
- Vendor outages match the normalized incident vendor name/key to registered vendors, then traverse
  direct vendor → service → owner relationships.
- Every result includes a deterministic `explanation` plus structured `affectedProduct`,
  `componentPath`, or `vendor` evidence. No advisory prose is interpreted, and no AI is called.

Matching is deliberately read-only and derived from the current global signal plus current
org-scoped inventory. Persisted findings and impact scores are later pipeline stages; this avoids
storing contextualization that silently becomes stale after an SBOM or relationship changes.

Security-news signals do not carry a package or vendor identity and are rejected by this endpoint;
their extracted CVE references remain available for a later article → advisory correlation stage.

## Impact scoring (Epic 7 stories 1-2)

`GET /api/scoring/change-signals/{id}` ranks one change signal across everything it reaches:

```
score = severity x exposure x criticality x 100
```

Three multipliers in `[0,1]`, so the score is a 0-100 percentage of the worst case and a service that
is weak on any one axis cannot be pushed to the top by the other two. Same read roles as matching
(`SERVICE_OWNER`, `ANALYST`, `COMPLIANCE_ADMIN`).

- **severity** — the advisory's published CVSS base score rescaled to `[0,1]`, taken as the highest
  across every feed reporting the same canonical vulnerability (NVD may score a CVE that OSV does
  not, and the reverse). With no CVSS, the signal's severity label is read instead — both NVD's
  `CRITICAL/HIGH/MEDIUM/LOW` and Statuspage's `critical/major/minor/none`, which is how a vendor
  outage is scored. An absent or unrecognised label falls back to a neutral `0.5`, never to zero:
  not knowing how bad something is must not rank it below something known to be mild. The response's
  `severity.source` says which of the three applied.
- **exposure** — the `ServiceExposure` declared on the service (`INTERNET_FACING` 1.0, `INTERNAL` 0.9,
  `ISOLATED` 0.5). The floor is well above zero because an isolated service is not invulnerable.
  Exposure is inbound attack reach, so it applies to vulnerabilities only: a vendor outage takes an
  internal service down as surely as an internet-facing one, and its breakdown says
  `exposureApplies: false`.
- **criticality** — the service's business tier (`CRITICAL` 1.0 → `LOW` 0.25).

Each scored service carries the full `breakdown` (every multiplier, enough to reproduce the score by
hand), its contributing `factors` (below), a deterministic `explanation`, and matching's `evidence`
chains for it (the response also carries the match counts), so a ranking can be reproduced and argued
with, and a page showing scores needs no second match request. A score is
banded into an `ImpactTier` (`CRITICAL` ≥ 90, `HIGH` ≥ 70, `MEDIUM` ≥ 40, `LOW` ≥ 10, else
`NEGLIGIBLE`) and marked `material` once it clears the materiality threshold (10.0). Immaterial rows
are still returned with their evidence — this ranks noise down, it does not hide it.

Matching's `confidence` is passed through rather than multiplied in: it answers whether the match
applies at all, which is a different question from how bad it is if it does, so a `POSSIBLE` row
keeps its real score and is flagged for review.

Owners are rolled up from the scored services, and an owner's score is their **worst** service's, not
the sum — owning two affected services does not make either more dangerous, and summing would rank a
team with many trivial hits above one whose payment gateway is about to be exploited.

Like matching, scoring is recomputed per request and persists nothing: a score derived from today's
SBOM and today's CVSS data would be a lie tomorrow, and the findings table that freezes one is a
later epic. Security-news signals are rejected by matching before scoring sees them.

### Why this score — contributing factors (CS203-72)

Every scored service lists its `factors`: one entry per term of the model, always in the order
`SEVERITY`, `EXPOSURE`, `CRITICALITY`, `CONFIDENCE`, so a client can render "why this number" without
knowing the model's shape.

| Field | Meaning |
|---|---|
| `factor` | Which term this is |
| `input` | What the multiplier was read from: `CVSS 3.1 base 9.8`, `severity major`, `INTERNET_FACING`, `HIGH`, `CONFIRMED`, … |
| `weight` | The multiplier, in `[0,1]` |
| `headroom` | Points the score would gain if this factor alone were at its maximum (1.0) — what it is costing the score |
| `limiting` | True for the factor with the most headroom: the one holding the score down (every one tied, if several are; none at the worst case) |
| `setting` | The `aegis.scoring.*` key that sets the weight, e.g. `aegis.scoring.exposure.internal`; `null` for a feed-published CVSS score or a built-in fallback |

Headroom rather than a "percentage share" because the model is a product, not a sum — a share would
pretend the terms are independent. Headroom reads the way an argument about a score actually goes:
"were this service internet-facing it would be 32 points higher". It is computed by re-scoring with
that one factor at 1.0, so `score + headroom` is exactly what the reader would see after the change,
rounding included. The `explanation` sentence names the limiting factor too:

```
CVE-2099-1 scores 32.0 (LOW) for Orders: severity 0.80 (CVSS 3.1 base 8) x exposure 0.50 (ISOLATED)
x criticality 0.80 (HIGH); exposure (ISOLATED) holds it down most, costing 32.0 points
```

Roll-up rows carry the same reasons for their headline number: each `ServiceRollup` and `OwnerRollup`
has `topFactors` and `topExplanation`, the factors of the score `topSignal` earned against that service.
The change-impact page in the frontend (`/advisories/{id}`) shows the score, tier, and factors on each
impacted service.

### Configurable weights

Every number the model multiplies lives under `aegis.scoring.*` (see `application.yml`), because the
arithmetic is not the part anyone argues with — the weighting is. One organization's LOW-tier internal
tool is another's revenue path. Omit any entry to keep its default, or the whole block to get the
shipped model; an override changes only what it names, so retuning one tier does not require
restating the table.

The defaults are **calibrated against the demo dataset (CS203-73)**, not picked by feel: the tiers are
the CVSS v3.1 bands (so on a CRITICAL internet-facing service the tier is the published rating), and
the weights are the smallest change to the original ones that satisfies a set of written anchors —
e.g. Log4Shell on the demo's internal Order Service is HIGH, a critical CVE is never immaterial, and an
outage is never discounted for exposure. [`SCORING-CALIBRATION.md`](SCORING-CALIBRATION.md) has the
anchors, the method, the reviewed demo table, and the known limits; `ScoringCalibrationTest` enforces
all of it against the real demo files, so a retune that breaks an anchor fails the build.

| Key | Default | What it moves |
|---|---|---|
| `aegis.scoring.criticality.*` | `1.0 / 0.8 / 0.5 / 0.25` | How much severity a business tier retains |
| `aegis.scoring.exposure.*` | `1.0 / 0.9 / 0.5` | What an attacker's reach is worth |
| `aegis.scoring.confidence.*` | `1.0 / 1.0` | Optional discount for an unreviewed (`POSSIBLE`) match |
| `aegis.scoring.severity-label.*` | `critical 0.95 … none 0.05` | Severity per feed word when there is no CVSS; add a word for a new feed vocabulary |
| `aegis.scoring.unknown-severity-factor` | `0.5` | Applied when neither a CVSS score nor a known label exists |
| `aegis.scoring.tiers.*` | `90 / 70 / 40 / 10` | Lower bound of each `ImpactTier` |
| `aegis.scoring.materiality-threshold` | `10.0` | Below this a result is returned but marked immaterial |
| `aegis.scoring.rollup.max-signals` | `200` | Ceiling on signals one roll-up may score |
| `aegis.scoring.rollup.default-window` | `P30D` | How far back a roll-up reaches with no `from` |

Each has an env-var override (`AEGIS_SCORING_TIER_CRITICAL`, `AEGIS_SCORING_EXPOSURE_ISOLATED`, …).
A present weight outside `[0,1]` is clamped into range and logged rather than trusted; the tier bounds
are the one exception — a non-descending or out-of-`[0,100]` ladder has no safe reading (every score in
the inversion would mis-tier), so it fails fast at startup rather than triaging wrongly in silence.
The confidence weights are the seam for the one judgement call story 1 deliberately left out: matching
confidence is *reported* rather than folded into the score, and a deployment that would rather rank
unreviewed matches below proven ones lowers `aegis.scoring.confidence.possible` instead of arguing
with the arithmetic. When it is not 1.0 the discount appears as its own term in the `breakdown` and in
the explanation, never hidden inside one of the other three.

Every scalar binds as a boxed type on purpose: an unset property binds a primitive to `0`, which is
indistinguishable from a deliberate `0` — and a silently-zero `materiality-threshold` would turn the
noise control off without anyone having configured that.

### Roll-ups (per service, per owner)

`GET /api/scoring/rollups` scores every signal in a window and aggregates it into one ranked view.
Where `/change-signals/{id}` answers "who does this CVE hurt", this answers "what is on our plate",
which is the question an owner actually opens the app with.

- Filters are the change-signal feed's own (`type`, `severity`, `source`, `search`, `ecosystem`,
  `from`, `to`) plus `limit`, so a roll-up can be narrowed to one ecosystem or one source.
- A service's or owner's `topScore` is their **worst** single signal, never a sum — twenty
  low-severity hits are not worse than one imminent breach, and a total would rank them that way.
  `signalCount` / `materialSignalCount` say how much else is queued behind it.
- The work is bounded twice: by the window and by `rollup.max-signals` (a caller's `limit` is clamped
  to it). Each signal costs a match and a graph traversal, so this is a ceiling on what one request
  can ask for. When the window held more, the newest are scored (by `occurredAt`, then id) and the
  response sets `truncated` — a roll-up that quietly covered an arbitrary subset would be worse than one
  that admits it.
- Security-news signals are skipped rather than rejected: they carry no package or vendor identity,
  and one unmatchable article must not fail the whole roll-up. Nor does any other single signal whose
  match fails: it is counted in `signalsSkipped` and the rest still rank.

### Determinism and ranking guarantees (CS203-74)

A score someone acted on must be reproducible later, and a work queue that reshuffles between two page
loads is not a queue. These are guaranteed, and each is enforced by a named test:

| Guarantee | Enforced by |
|---|---|
| A score is the **exact decimal** product of the factors shown, rounded half-up — the hand calculation, never a floating-point neighbour of it (checked for every CVSS score 0.0-10.0 x every service profile) | `ScoringDeterminismTest` |
| The same signal and estate give the **same response** — on a rerun, and whatever order matching or the database hands things over in | `ScoringDeterminismTest` |
| **Ties break stably**: equal scores by name (ignoring case), then id; equal CVSS scores across feeds by the newer CVSS version, then feed; a roll-up tie goes to the newest signal | `ScoringDeterminismTest` |
| A worse input **never ranks lower**: more severe, more exposed, more critical, confirmed over possible, a worse outage word | `ScoringRankingTest` (full input grid) |
| The **tier never contradicts the score**, and *immaterial* is exactly *NEGLIGIBLE* | `ScoringRankingTest` |
| Every list is in **score order**; an owner stands exactly where their worst service stands | `ScoringRankingTest` (randomised estates, fixed seed) |
| The **roll-up agrees** with scoring each signal on its own — same top score, same reasons, same counts | `ScoringRankingTest`, `ScoringApiIntegrationTest` |
| A truncated roll-up covers the **newest** signals; one failing signal is **skipped**, not fatal | `ScoringApiIntegrationTest`, `ScoringRollupResilienceIntegrationTest` |

Writing these tests found four real defects, each reproduced by a failing test before it was fixed:

- **Floating-point rounding.** Scores were multiplied as doubles, so 0.07 x 0.5 x 0.5 = 1.75 came out as
  1.7499… and displayed 1.7 — 98 of 2,424 CVSS combinations read a tenth low, and a CVSS 9.8 showed its
  factor as `0.9800000000000001`. Products are now exact decimals.
- **Unsorted roll-up page.** The roll-up asked the store for an unsorted page, so "the newest are scored"
  was not true: Postgres returned the `change_signals` view in scan order, and a truncated roll-up
  scored the *oldest* signal first (100.0 instead of 50.0 in the integration test).
- **Feed-order CVSS.** When two feeds published the same base score under different CVSS versions, the
  version reported — and the explanation sentence — depended on which row the database returned first.
- **The roll-up's skip never worked.** A signal whose match threw marked the roll-up's shared transaction
  rollback-only; the roll-up caught it, stepped over the signal, and then failed with a 500 at commit.
  Each signal now runs in its own transaction.
