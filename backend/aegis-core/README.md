# Aegis Core

The domain and pipeline service for Aegis (Java / Spring): the inventory & map of the estate,
advisory ingestion, matching, scoring, and the execution of approved actions. The AI steps
(**assess** and **draft**) live in a separate Python **ai-service**, reached through the `ai` port.
See the root [`ARCHITECTURE.md`](../../ARCHITECTURE.md), [`BUILD_PLAN.md`](../../BUILD_PLAN.md), and
[`docs/adr/0001-aegis-architecture-and-multi-tenancy.md`](../../docs/adr/0001-aegis-architecture-and-multi-tenancy.md).

> **Status:** the Spring Boot wiring, security, module seams, and AI port are in place. The inventory
> module persists its core entities and direct relationships, exposes authenticated CRUD APIs, and
> answers "Used by" (a package in any version, or a vendor → the services using it, directly or through
> which component, in each service's own dependency graph); the ingestion framework (scheduled polling, dedup, raw-event store) runs four
> connectors — the **NVD CVE feed** (CPE, CVSS and affected versions → advisories), **OSV**
> (ecosystem-native advisories keyed by purl; off by default), **vendor status
> pages** (Statuspage/RSS → vendor incidents), and **security news** (RSS/Atom → articles with the CVE
> identifiers they mention) — unified for reading through the **`change_signals`** view, with
> per-feed health/counts/last-success exposed as Prometheus metrics. **Deterministic matching is
> built:** advisory CPE/package + SemVer range matching, dependency and vendor traversal, and explicit
> evidence chains. **Impact scoring is built:** every impacted service is scored
> `severity x exposure x criticality`, banded into a tier and rolled up per owner and per service,
> with every weight in the model configurable and every score's contributing factors surfaced.
> **Three responses are built**, each drafted by one person and approved by another: safe-upgrade draft
> pull requests, risk tickets filed in the organization's Jira, and DORA regulator memos. Persisted
> findings and the audit trail remain planned.

## Modules (a modular monolith, split by seams later)

Top-level packages are the seams; ArchUnit (`PackageStructureArchTest`) enforces the boundaries below —
persistence stays behind each module's `Store` port, the ingestion framework stays independent of its
connectors and the domains, `platform` depends on no feature, no controller lives under `ingestion`,
and the modules are cycle-free.

| Package | Responsibility |
|---|---|
| `platform`   | Cross-cutting foundation: the `ApiError` contract, security/OpenAPI config, tenancy (`CurrentOrg`), sealing stored credentials (`crypto.SecretBox`), ping |
| `ingestion`  | The pipeline framework (scheduled poll → dedup → raw-event store → drain); source connectors under `ingestion.connectors` (NVD, OSV, vendor status, security news) |
| `advisories` | Normalised advisories (NVD/OSV) + read API |
| `incidents`  | Normalised vendor incidents (status pages) + read API |
| `news`       | Security-news articles with extracted CVE references + read API |
| `signals`    | The unified read-only `change_signals` view over advisories, incidents, and news |
| `inventory`  | Services and their repository links, components, vendors, SBOM dependencies, ownership — the map. Each service has its own dependency graph (`DependencyGraph`, V29): the one walk behind matching, the upgrade recommendations and "Used by" |
| `matching`   | Advisory package + version range → components → impacted services/owners + evidence chains |
| `scoring`    | Deterministic impact score per service and owner, with a "why" breakdown |
| `actions`    | Responses: what to respond to next (the triage queue, `triage`, E9 story 1), the draft-PR port + GitHub App adapter and safe-upgrade proposals (`pr`, `upgrade`, E8 story 4), the issue-tracker port + Jira adapter and risk tickets (`tracker`, `ticket`, story 5), and DORA regulator memos (`memo`, story 6) |
| `notifications` | Owner notifications (E9 story 5): a scheduled job tells each service owner once — in the app and by email through identity-service — about every triage item in their services that needs a decision |
| `audit`      | The decision audit trail (E10 story 1): every step a response takes, with who took it, when, why and the response as it stood then; append-only |
| `ai`         | **Port** to the Python ai-service: `AiAssessor` / `AiDrafter` + DTOs + `HttpAiClient` |

## Triage queue and item detail (E9 Stories 1–2)

`GET /api/findings?serviceId=&ownerId=&mine=true&ecosystem=npm&tier=HIGH&page=0&size=20` (`actions.triage`) is
what to respond to next, highest impact first. Each item is one thing in one service:

- a **vulnerable dependency** (`kind: VULNERABILITY`, `upgrade`): the service's upgrade recommendation — the
  package, the version that clears every advisory with a fix, the vulnerabilities it clears, and its readiness
  (why the response is the ticket alone, when it is). It is the row the service's own page lists, from the same
  batched search (`UpgradeRecommendations.recommendFor`), so both rank and score it the same way;
- a **vendor outage still in progress** (`kind: VENDOR_OUTAGE`, `outage`): not resolved on its status page and
  updated within the last 14 days (`TriageQueue.OUTAGE_QUIET_AFTER`, since a plain RSS feed never says
  "resolved"), each scored once through `ScoringService`. An outage that can't be scored is logged and left out
  rather than failing the queue.

Only material items are listed. Each carries its owners, the response already drafted for it (`response`) and
whether the caller may draft one (`canDraft`: an analyst, or one of the service's owners, as `TicketRoles.PROPOSE`
enforces). Every filter is optional: service and owner choose the services, ecosystem keeps vulnerable
dependencies only, and tier is the item's impact tier. `mine=true` limits the queue to the services whose assigned
owner is the JWT subject. The `services` and `owners` filter options cover the whole queue, the `ecosystems` the
services chosen. Ranking (score, then service name,
kind and package or title) and filtering happen before paging; `items` is a Spring page. The domain roles read it
(`InventoryRoles.READ`). It is recomputed on every request and persists nothing, so there is no migration. A
security news item names no package or vendor, so it has no score and no place in the queue.

`GET /api/findings/my-services` is the queue as a service owner looks after it (E9 story 2): each service whose
assigned owner is the JWT subject, by name, with its items (`toRespond`), how many have no response yet
(`noResponseYet`), the worst one's score and tier, the responses for it still waiting in Review & approve
(`awaitingDecision`, counted as `pending=true` counts them), and its repository and last SBOM import — without a
repository a response is the ticket alone, and without an SBOM only the components linked by hand are checked.
The items are the queue's own, so the two never disagree. Someone who owns no service gets an empty list.

Routing (E9 story 4) is the service's current owner assignment, read on every request: nothing is routed or
stored per person, so when governance reassigns a service its items, its drafted responses and the right to
draft and decide them follow the new owner at once, and the previous owner keeps none of them
(`TriageApiIntegrationTest.reassigningAServiceRoutesItsItemsAndResponsesToTheNewOwner`).

The frontend's `/findings` renders the queue: each row links its response or offers **Draft response**, and a
drafted response then waits in `/responses/review` for a decision. `/findings/mine` (**My services**) is a service
owner's page, in the sidebar only for the `SERVICE_OWNER` role (only a service owner can be assigned a service):
the overview above, then the queue for those services, with no owner to filter by. `/findings/{changeSignalId}`
is the item detail: the change-impact view with the original change, every
impacted service and owner, score factors and match evidence, and each service's drafted response and upgrade
edits. Persisted findings with a lifecycle status are a later story; owners are notified of new items (below).

## Owner notifications (E9 Story 5)

When an item in the triage queue needs a decision — **New** (no response yet) or **In review** (drafted,
undecided) — each assigned owner of its service is told **once**: a notification under the bell in the app, and
an email. Whoever drafted a response in review isn't told it needs their decision (it needs someone else's, or,
with no pull request, they file it themselves). A scheduled job (`OwnerNotificationJob`, every
`AEGIS_NOTIFICATIONS_INTERVAL`, on the instance holding the scheduler lease) asks identity-service for every
organization, then in each one reads the triage queue for the services that have an owner (`TriageQueue.assigned`,
the same items My services shows) and records one row per owner and item (V35, `owner_notifications`, unique on
organization, owner, service and item). An item is a vulnerable package by its canonical vulnerability id (a CVE),
whichever feed's advisory represents it today, or an outage by its incident; the notification's page follows the
item's change signal. Approved, actioned or closed items tell nobody; a newly assigned owner is told about what is
already waiting.

- **Never emailed twice about the same item.** Everything due for one owner goes in one email (50 items at most),
  claimed under a delivery id before it is sent. identity-service sends each delivery id once, so whenever aegis-core
  can't tell whether an email went out — a failure or timeout, a crash before it recorded the answer — it asks again
  under the same id: after a failure with growing waits (5 minutes, then four times longer, up to 6 hours), after a
  crash once 15 minutes have passed. It gives up (`FAILED`) after six tries, about thirteen hours, or at once when
  identity-service refuses the request itself.
- **identity-service sends it**, through its existing `EmailSender` (Mailhog locally,
  [http://localhost:8025](http://localhost:8025); Amazon SES deployed), from its internal API
  (`POST /internal/notifications/owner-email`; `GET /internal/organizations` lists the organizations). That API
  takes the service token (`IDENTITY_SERVICE_TOKEN` here, `IDENTITY_INTERNAL_SERVICE_TOKEN` there), not a user's,
  because no user is asking; the gateway never routes it. It emails only an enabled, verified, active member of
  the organization — anyone else is `UNREACHABLE` and sees the item in the app only — and it writes the email
  itself from the items, each linking to a path in the app (`/findings/{changeSignalId}`). The only links in the
  email are the app's: an outage's title is its vendor's status page wording, quoted and attributed, and
  identity-service removes any URL from item text (and defangs a host with a path, `github[.]com/…`).
- **Off by default** (`AEGIS_NOTIFICATIONS_ENABLED`); docker compose turns it on with a dev token and a one-minute
  interval. The first run in an organization tells every owner about everything already waiting, in one email
  each.

| Method | Path | Who | What |
|---|---|---|---|
| GET | `/api/notifications?unread=&page=&size=` | any domain role | Your own notifications in your active organization, newest first |
| GET | `/api/notifications/unread-count` | any domain role | `{unread}` |
| POST | `/api/notifications/{id}/read` | any domain role | Mark one of yours read → 204 (404 if it isn't yours) |
| POST | `/api/notifications/read-all` | any domain role | `{marked}` |

The frontend's bell (`components/app/notification-bell.tsx`, in the top bar for service owners, the only ones ever
notified) shows the unread count, checked every minute, and the latest ten; opening one marks it read and goes to the
item.

## Decision trail (E10 Story 1)

Every step a response takes is recorded as it happens (`audit`, V36 `decision_records`): a risk ticket drafted,
edited, overridden (and the override withdrawn), approved, filed or failed to file, dismissed; its upgrade pull
request drafted, approved, opened or failed to open, dismissed; a regulator memo drafted, approved, dismissed. Each
record keeps who took the step (`actorId`), when (`occurredAt`; `sequence` orders records within one instant), why
(`reason`: an override's, a dismissal's, a failure's), the change, and the service's name, score and tier as they were
then, and the response itself as it stood (`state`, JSON: the draft, any edit or override, the evidence with the score
arithmetic, the decision and its result).

- **A step and its record commit together** (`DecisionTrail.recorded`): a record that can't be written fails the
  step, and the response stores refuse a write made outside the trail (`DecisionTrail.UnrecordedStep`), so a new path
  that forgets it fails instead of leaving a gap. An issue filed or a pull request opened is never recorded as failed:
  if Aegis can't record that outcome, the response stays filing (or the pull request opening) and the approver is told
  the issue's key or the pull request's address (409).
- **Append-only**: a trigger refuses every update, delete and truncate, from any role, the superuser included. It is
  not a lock against the table's owner, today the role aegis-core connects with, which can disable a trigger; closing
  that needs the owner/app role split in `todo/aws-deployment.md`.
- **Copies, not references**: no foreign keys, so a record outlives its service, ticket or memo, and a later SBOM
  import, re-score or edit never changes it. Org-scoped, with the row-level-security backstop.
- **A service's trail** holds its tickets' and pull requests' records (`serviceId`) and those of every regulator memo
  that covers it (`serviceIds`: a memo names no single service). A pull request's records carry its score and tier as
  drafted, which are its ticket's. Responses decided before V36 have no records (not backfilled), and pull requests
  drafted before V36 no score.

| Method | Path | Who | What |
|---|---|---|---|
| GET | `/api/audit/decisions?serviceId=&changeSignalId=&responseId=&actorId=&action=&subject=&from=&to=&page=&size=&sort=` | `ANALYST`, `COMPLIANCE_ADMIN` | The trail, oldest first (50 a page by default; `sort=sequence,desc` for newest first), each record without its `state`. `serviceId` with `changeSignalId` is one item's chain; `responseId` one response's (a ticket and its pull requests share the ticket's id). Search (E10 Story 2): `actorId` the person who took the step; `action` and `subject` any of several (comma-separated or repeated); `from`/`to` a period as ISO-8601 instants, `from` inclusive and `to` exclusive. Filters combine. A period that doesn't end after it starts, an unknown value, or any sort but `sequence` (or `occurredAt`, the same order) is a 400 |
| GET | `/api/audit/decisions/{id}` | `ANALYST`, `COMPLIANCE_ADMIN` | One record, with `state` |
| GET | `/api/audit/decisions/removed-services` | `ANALYST`, `COMPLIANCE_ADMIN` | The services the trail covers that are no longer in the inventory, each with the name its latest ticket's or pull request's record gave it (`null` when only memos covered it): still searchable and exportable |
| GET | `/api/audit/evidence?serviceId=&from=&to=&format=csv\|pdf&zone=` | `ANALYST`, `COMPLIANCE_ADMIN` | A service's evidence for `[from, to)` as a file (`Content-Disposition: attachment`): CSV by default, or PDF with times in `zone` (IANA, default UTC) and the CSV of the same records attached. Headers `X-Evidence-Records` and `X-Evidence-Csv-Sha256` (that CSV's). `serviceId`, `from` and `to` are required; more than 5,000 records (2,000 for a PDF), an unknown format or zone, or a period that doesn't end after it starts is a 400; 429 while other exports are being prepared; 502 if the member directory can't be reached |

**Searching the trail (E10 Story 2).** A compliance admin answers an auditor's question from **Reports → Audit log**
(`/reports/audit`): the trail filtered by service, by the person who took each step (named from the organization's
member directory), by the step and what it was taken on, and by a period of whole days in the reader's time zone
(sent as `from` = the first day's start and `to` = the start of the day after the last), newest first by default.
Each record opens to the response as it stood then. A person's search is indexed (V37, `org_id, actor_id, seq`); a
period uses V36's `org_id, occurred_at`.

**Exporting a service's evidence (E10 Story 3).** From **Reports → Evidence** (`/reports/evidence`), a service and a
period (whole local days, the last 90 by default) export everything the trail holds for that service in that period —
its risk tickets' and upgrade pull requests' steps and those of every regulator memo covering it — oldest first, after
a preview of how many records there are and the latest few (`EvidenceExport`, `EvidenceController`). The service is
found by searching the inventory, or among the removed services whose decisions are still on record.

- **CSV** (`EvidenceCsv`): one row per record with every field, the people's names beside their ids, and `state` (the
  response as it stood) as JSON. RFC 4180, every field quoted, UTF-8 with a byte-order mark, times in UTC. Every field
  is kept as recorded, except that one a spreadsheet would run as a formula (`=`, `+`, `-`, `@`, a tab or carriage
  return first: change titles come from feeds) is written with a `'` before it. The bytes depend only on the records
  and names, so the same evidence always has the same SHA-256.
- **PDF** (`EvidencePdf`, Apache PDFBox): what was exported, by whom and when, the record count, and the CSV of the same
  records **attached** (a PDF file attachment; the viewer opens on its attachments) with its SHA-256 printed; then each
  record (when, the step, who, the impact then, the change, why); then each record's snapshot. Times in the reader's
  time zone. The standard fonts print Latin-1 only; other characters print as `?` (the attached CSV keeps them).
- **One reading.** An export reads every page of the trail in one read-only transaction at repeatable-read isolation,
  so a decision committed meanwhile can't shift a page (a record skipped, another repeated), and a PDF and the CSV it
  carries always agree. A CSV downloaded separately is another reading: it differs if a decision was made or a name
  changed in between, which is why the PDF carries its own. Both come from the trail search's filters (`serviceId`,
  `from`, `to`), so they hold exactly the records the Audit log shows for that service and period
  (`EvidenceExportIntegrationTest` checks it field by field, snapshots included).
- **Bounded.** Records are read 500 at a time and written straight to temporary files (the PDF through PDFBox's
  temporary-file cache), then sent and deleted, never held whole in memory; the frontend streams the file through.
  At most 5,000 records per CSV and 2,000 per PDF, and two exports at once per instance (`EvidenceExport.AT_ONCE`; a
  third gets 429).
- **Tested from real decisions (CS203-144).** `EvidenceExportCorrectnessIntegrationTest` drafts, overrides, approves,
  files and dismisses a service's responses through the API at three moments, then exports the three back-to-back
  periods between them as CSV and as PDF. Each export holds every decision recorded for the service in its period and
  nothing outside it, checked against the service's whole trail filtered by time in the test (not against the export's
  own query); the PDF names no record from outside its period; and the periods together hold every record exactly
  once, so a period's end is excluded and its start included. A decision committed while an export is read (one that
  drew an earlier sequence) is neither read nor shifts the pages.
- **Names**: the service as `inventory` names it now (a removed service's records keep it exportable under the name
  they gave it); people from the whole member directory as the exporter sees it, listed in pages of 500 before the
  reading. A member by their display name, a member without one as "Member" and the start of their id, anyone no
  longer a member as "Former member" and the start of their id: an export goes to auditors, so it carries no email
  addresses (the full id is in the CSV). If the directory can't be reached, the export is refused (502) rather than
  made with some names missing.
- **Times** (V38): a record's `occurred_at` is read as the row is inserted (`clock_timestamp()`), as its sequence is,
  so the trail's order and its times agree. Records written before V38 keep their transaction's start time.

## Service repositories and draft PR providers (E8 Story 2)

`PUT /api/inventory/services/{serviceId}/repository` accepts `{"url":"https://github.com/acme/payments","branch":"develop"}`
and returns the updated service. `DELETE` on the same path unlinks it (204). Service responses include
nullable `repositoryUrl` and `repositoryBranch`; omitting the branch selects the repository default.
The governance service-update endpoint preserves these fields. Analysts, compliance admins and the
service's assigned owners can maintain the link; reads and writes remain scoped to the active org.
The service detail page's **Repository** section links the repository and imports its SBOM in one step
(see [SBOM import](#sbom-import-cyclonedx)), then re-imports it on demand.

`RepositoryUrl` is shared by repository linking and SBOM generation. Both use
`aegis.inventory.sbom-generation.allowed-hosts`, even when SBOM generation is disabled. It takes what
people paste and stores the canonical HTTPS URL: an `https://` URL, an SSH clone URL
(`git@github.com:owner/repo.git`, `ssh://git@host/…`), a clone URL carrying a username
(`https://jdoe@bitbucket.org/…`, dropped), or a browser link into a branch or file (GitHub and Bitbucket
repositories are two segments; GitLab's `/-/` ends the project path on any host). Hosts are lower-cased
and `.git`/trailing slash removed; query strings and fragments are ignored. Passwords, custom HTTPS
ports, dot segments anywhere, invalid path segments and unsafe branch/tag refs are rejected. Credentials
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
installation tokens, branch/file commits and draft PR creation. It ships **disabled**, and even when
enabled it mints a token only for an installation the caller's organization has **connected**.
Tests use a local fake GitHub server and temporary RSA keys. Its callers are the approval step below and
the SBOM import's clone. An audit trail (E10) is still to come. See [GITHUB_APP.md](GITHUB_APP.md) for
configuration (including docker compose), permissions, key handling, test evidence and the sandbox smoke
procedure.

### Connect GitHub (per organization)

GitHub knows that the App is installed on an account, never which Aegis organization it is for, so each
organization's admin connects its installations (`github_connections`, V22, org-scoped with RLS):

| Method | Path | Who | What |
|---|---|---|---|
| GET | `/api/integrations/github` | any role in the organization | `{enabled, installUrl, connections}` |
| POST | `/api/integrations/github/authorizations` | `ORG_ADMIN` | → `{authorizeUrl}`: the GitHub sign-in, carrying a one-time state bound to the admin |
| POST | `/api/integrations/github/connections` | `ORG_ADMIN` | `{code, state}` from GitHub's redirect → `{connected, skipped}` |
| DELETE | `/api/integrations/github/connections/{installationId}` | `ORG_ADMIN` | Disconnect (the App stays installed on GitHub) |

Completing a connection exchanges the code for a short-lived user token, lists the App installations that
person can see, and connects only those they **control** — their own account, or a GitHub organization
they administer — reporting the rest with the reason. The state is single-use, expires after ten minutes
and must come back to the person and organization that started it; the user token is revoked and never
stored. With the App disabled, the status says so and every change is a 409. The frontend page is
**Organization → Integrations** (`/admin/integrations`); GitHub returns to `/admin/integrations/github/callback`.

## Upgrade recommendations (no CVE to look up)

`GET /api/actions/upgrade-recommendations[?serviceId=]` (any domain role) lists every vulnerable dependency
of the organization's services — or one service — highest impact first, found from the services' side:
`matching.VulnerableDependencies` walks each service's direct components down the dependency graph, asks
the advisory store which advisories name any of those packages, and applies matching's own identity and
version rules, so a dependency is listed exactly when that advisory's impact page would list the service.
Each item carries the fixed version (`UpgradeTargets`, clearing every advisory with a fix), the impact
score (`ImpactScorer`: severity x exposure x criticality), every manifest declaring it (`manifestPaths`),
the vulnerability a response would answer (`changeSignalId`), the response already drafted for it
(`response`, through the ticket module's `ResponseDirectory`) and the pull request upgrading it
(`proposal`), where the service's code imports it (`usage`, and `viaUsage` for what pulls a transitive one in —
below), and a readiness: `READY` — bumping its declaration, or, for a package another one pulls in,
overriding it in the files `overrideIn` names (below) — `NO_REPOSITORY` (link it), `TRANSITIVE` (another
package pulls it in and Aegis can't tell where to override it: no lockfile recorded for an npm package, or what
pulls it in is declared only in a file Aegis doesn't edit), `UNSUPPORTED` (an ecosystem or file Aegis does not
edit) or `NO_FIX`, with the reason, plus `canDraft` for the caller. Recomputed per request, in a fixed number of batched lookups however
many services there are: `VulnerableDependencies.find` loads every advisory naming the packages, and each
target is worked out in memory from them (`UpgradeTargets.resolve(vulnerability, component, known)` — the
same answer drafting gets, which a test pins). Nothing is drafted until a person clicks **Draft response**:
on **Review & approve** ("Recommended upgrades", direct dependencies across the estate) and on each
service's page ("Vulnerable dependencies", including transitive ones).

## Responses: a risk ticket and its upgrade pull request (E8 Stories 4–5, with an early slice of E9)

A **response** answers one vulnerability (or vendor outage) in one service: its **risk ticket** — the record
of the risk, filed in the organization's Jira — and, for a vulnerability, the **one upgrade pull request**
that fixes it, drafted and approved together. Nothing leaves Aegis without an approval; the PR is always a
**draft** on an `aegis/` branch, and Aegis never merges.

| Method | Path | Who | What |
|---|---|---|---|
| POST | `/api/actions/risk-tickets` | an analyst, or the service's assigned owners | Draft a response: `{changeSignalId, serviceId}` → 201, the ticket `PROPOSED` with its `pullRequests` |
| GET | `/api/actions/risk-tickets?serviceId=&changeSignalId=&status=A,B&pending=` | any domain role | Newest first; `pending=true` keeps those that still need a person (the ticket or its PR awaits a decision, or failed) |
| GET | `/api/actions/risk-tickets/{id}` | any domain role | With its pull requests and `serviceNow`: the ticket as ServiceNow incident fields |
| POST | `/api/actions/risk-tickets/{id}/approve` | an analyst, or the service's assigned owners — **never the drafter of a response with a PR** | Optional `{version}`. Files the ticket in Jira → `FILED`, then opens the PR under the ticket's key → `OPENED` (or `FAILED`, with the reason, on the part that failed) |
| POST | `/api/actions/risk-tickets/{id}/dismiss` | an analyst, or the service's assigned owners (the drafter included) | `{reason, version?}` (reason required) → the ticket and its PR `DISMISSED` |
| POST | `/api/actions/risk-tickets/{id}/edit` | an analyst, or the service's assigned owners | While `PROPOSED`: `{version, summary, risk, impact, actions}` → the narrative as it will be filed; the first draft is kept in `edit` |
| POST | `/api/actions/risk-tickets/{id}/override` | an analyst, or the service's assigned owners | While `PROPOSED`: `{version, priority, dueDate?, reason}` (reason required; no due date is none, never one already over everywhere) → filed with these; Aegis's own assessment is kept in `override`. Overriding again may change only the reason; Aegis's own values are refused (withdraw instead) |
| POST | `/api/actions/risk-tickets/{id}/override/withdraw` | an analyst, or the service's assigned owners | While `PROPOSED`: `{version}` → back to Aegis's own priority and due date, `override` null |
| POST | `/api/actions/risk-tickets/{id}/pull-requests` | an analyst, or the service's assigned owners | Draft the PR of a response that has none (left out, or its repository linked afterwards) |
| POST | `/api/actions/risk-tickets/{id}/pull-requests/{proposalId}/approve` | an analyst, or the service's assigned owners — **never the PR's drafter** | Open a filed response's PR on its own: drafted afterwards, or failed |
| POST | `/api/actions/upgrade-proposals/{id}/dismiss` | an analyst, or the service's assigned owners (the drafter included) | Leave the PR out of its response: `{reason}` → `DISMISSED` |
| GET | `/api/actions/upgrade-proposals?serviceId=&changeSignalId=&status=A,B&standalone=` | any domain role | Newest first; `standalone=true`: only those drafted on their own before responses |
| GET | `/api/actions/upgrade-proposals/{id}` | any domain role | The exact PR title and body, and every edit |
| POST | `/api/actions/upgrade-proposals/{id}/approve` | an analyst, or the service's assigned owners — never the drafter | Only a PR drafted on its own before responses (409 for a response's) |

- **Four eyes where a repository changes** (ADR §6e, RTS 2024/1774 Art. 17(1)(b)). Approving a response
  with a pull request is never its drafter's, retries included (403). A response without one only records
  a risk (Art. 10(2)(h)), so its drafter may file it — and leaving a PR out (dismissing it) turns a
  response into one. A compliance admin reads all of it — the queues, each response with who drafted and
  who decided, the history — to audit the decisions, and makes none of them. The recommendations'
  `canDraft` follows the same rule.
- **One pull request per vulnerability per repository** (`UpgradeProposalService.plan`): every direct
  dependency the vulnerability matches for the service, in **every manifest** the SBOM import found it
  declared in (V26: `business_service_components.manifest_paths`; the ecosystem's manifest at the root only
  when nothing was recorded — an uploaded SBOM, a component linked by hand), and **every package another one
  pulls in, overridden** in each project that resolves it (below). A dependency another current PR
  already upgrades is left to it; what can't be automated — an override with no recorded lockfile to place it
  by, an unsupported ecosystem, no fix, no
  linked repository, **found only in files Aegis doesn't edit** (a Gradle lockfile, `poetry.lock`,
  `requirements-dev.txt`, a jar), or **a repository host no pull-request provider serves** (only the GitHub App
  opens PRs, so GitLab and Bitbucket get tickets) — is recorded on the ticket as `Not automated: …` evidence
  instead, and the recommendation says so up front (`UNSUPPORTED`, with the reason) rather than failing at
  approval. A dependency also found in such a file gets its editable manifests upgraded and the ticket names
  the rest. Edits are JSON on the proposal (V27); several in one file are applied in turn.
- **The safe version is deterministic** (`matching.UpgradeTargets`): starting from the installed version,
  move to the fixed version of whichever affected range still contains it, across **every** advisory
  Aegis holds for the package, not only the one being remediated (Log4Shell's own fix, 2.15.0, was still
  vulnerable). A remediated range with no published fix, or a version that cannot be compared, is not
  automated; another advisory without a fix is reported in the PR body rather than blocking.
- **The edits are deterministic and minimal**: Maven `pom.xml` (the dependency's `<version>` or the
  property it reads — set in the pom itself when only a parent defines it; a version a parent POM or BOM manages
  is pinned in the pom's own `<dependencyManagement>`), npm `package.json` (keeping `^`/`~`/`>=`) and PyPI
  `requirements.txt` (`==` pins; a bare requirement or a lone lower bound gets the fix as its lower bound). An
  unfamiliar spec is refused with the reason, never approximated. Aegis runs no
  package manager, so an npm PR leaves the lockfile beside each `package.json` to the reviewer: it opens with
  **Finish it before merging**, and its first checklist item names every folder whose lockfile to refresh and
  commit to the branch. Until that is done the lockfile still pins the old version — `npm ci` refuses the
  mismatch, and the next SBOM import still finds the vulnerable version.
- **A package another one pulls in is overridden** (`Overrides`, `ManifestEditor.override`): the project pins its
  version, so every copy of it in the tree moves to the fixed one, even where what pulls it in asks for an older
  range. Where the override goes is the project that resolves it. For npm, that is the project of each lockfile the
  import recorded for the dependency that pulls it in (V39): npm's `overrides` in that `package.json`, yarn's
  `resolutions` there, pnpm's `overrides` in its `pnpm-workspace.yaml` (pnpm 10 reads it there, pnpm 11 only
  there; the file is created when missing). For Maven, an entry in the `<dependencyManagement>` of each pom
  declaring what pulls it in; for PyPI, a requirement of its own in each `requirements.txt` that does (`==` when
  the file pins everything that way, else `>=`). An npm override asks for `^` the fixed version; several versions of
  one package overridden in one file take the highest target. The PR marks each override with what pulls it in and
  explains the mechanism; a package a lockfile resolves that no manifest declares is overridden the same way.
  Without a recorded lockfile (an import before V39) an npm one is `TRANSITIVE` until the service is re-imported.
  An override stays until a person removes it, and the next fix raises it in place. When a package the project
  overrides is (or becomes) declared, its bump takes the override along: in the same `package.json` it is set to the
  new declaration (npm refuses an override of a direct dependency at any other spec), and an override in the
  project's root `package.json` or `pnpm-workspace.yaml` is raised by an edit that only follows the bump
  (`VersionOverride.ifPresent`) — left alone, and the file untouched, when there is none.
- **The ticket is filed first, then the PR opens under its key** (`SEC-12: Upgrade axios to 1.8.2 in 2
  manifests (CVE-…)`, and in the body), so the tracker links them — with the GitHub for Jira app, the issue
  shows its PR, and a Jira Automation rule can close the issue when the PR merges ([JIRA.md](JIRA.md)); Aegis
  never edits an issue. The Jira description lists what the PR will change. If the PR then fails to open,
  the ticket stays filed and the PR is retried on its own.
- **The repository is the service's own link**, never a caller's input; approval refuses if the link
  changed after drafting. At approval each manifest is read again, the edits re-applied, and each write
  pinned to the revision just read, so a manifest edited in between is refused rather than overwritten.
- **The PR body** states the facts (every edit, advisories checked, score, match confidence, evidence) and a
  reviewer checklist. The ai-service's rationale, when available, is quoted and labelled unverified; an AI
  outage never blocks a fix. Feed- and model-written text is made inert (no @-mentions, cross-references,
  remote images or HTML).
- **The ticket: Aegis's facts, the AI's words.** The title, priority and due date (from the impact tier:
  CRITICAL → Highest, due in 7 days; HIGH → High, 30; MEDIUM → Medium, 90; LOW → Low, 180; NEGLIGIBLE →
  Lowest, no due date), labels and evidence are deterministic. The summary, risk, impact and recommended
  actions are the ai-service's draft (`risk_ticket`), cited, or a plain fallback from the change's own
  description when the AI is unavailable — which never blocks a response. The Jira description is Atlassian
  Document Format built from text nodes only, so nothing a feed or the model wrote is read as markup.
- **Decisions are safe under concurrency**: each part is claimed first (optimistic lock) — `FILING`, then
  `OPENING` — so two people approving at once get one issue, one PR and a conflict; a claim interrupted for
  ten minutes can be retried, and a failure is recorded on the part that failed. One current response per
  service and change (V23, and `FAILED` counts), one current PR per response (V27). Every store locks the row
  while it checks and writes a decision (`select … for update`), so a decision racing another gets the
  conflict message, never a server error. A decision may also carry the `version` the reviewer read (edits and
  overrides must): if someone edited, overrode or decided the response since, it is a 409 — "This response
  changed after you opened it" — and nothing changes. The UI always sends it.
- **Edit and override before deciding (E9 story 3, V33)**, only while the ticket is `PROPOSED`, by whoever may
  decide it; a compliance admin may do neither. *Edit* rewrites the summary, risk, impact and actions in place
  (blank actions dropped, redacted like every draft) and keeps the draft as first written in `edit`
  (`draftedSummary`, `draftedRisk`, `draftedImpact`, `draftedActions`, `by`, `at`). *Override* replaces Aegis's
  priority and due date with a required reason and keeps Aegis's assessment in `override` (`assessedPriority`,
  `assessedDueDate`, `reason`, `by`, `at`); overriding again keeps Aegis's, not the previous override's, and may
  change only the reason; *withdrawing* it goes back to Aegis's own values. A due date is refused only once that
  day is over everywhere (UTC−12), so no reviewer's today is refused; the page keeps them from picking their own
  yesterday. Reasons are cut to their 1,000-character column after redaction, which can lengthen them. The
  ticket's own fields are always what gets filed, and the Jira description records an override and its reason,
  says when the AI's draft was edited (its citations then labelled as the original draft's), and claims an
  approval only once there is one — "by a second person", or "by the person who requested it" for a response
  without a pull request, which its drafter may file. Neither changes who may approve: the four-eyes rule is about
  the pull request, which neither touches. The page saves an edit or override against the version it opened on,
  and holds the decision while an edit is unsaved.
- **Every item has a stage** (`ResponseStage`): **New** (no response yet) → **In review** (drafted) →
  **Approved**, or **Overridden** when the priority or due date was overridden → **Actioned** (the ticket is
  filed) or **Closed** (dismissed). A failed filing stays Approved or Overridden: the decision stands and is
  retried. Each response carries `stage`, and so does each triage item (`GET /api/findings`: **Closed** when its
  last response was dismissed and none replaced it — drafting another reopens it) and each recommendation's
  `response`. Who approved a response is kept in `approval` (V34), apart from `decidedBy`, whoever decided last,
  so dismissing a response whose filing failed doesn't lose its approver. The response page shows the stage as a
  stepper, with who moved it along and when.
- **Jira is connected per organization** (`jira_connections`, V24): its organization admin grants Aegis one
  Jira site through Atlassian's consent screen under Organization → Integrations, then chooses the project
  and issue type. Tokens are sealed with `AEGIS_SECRETS_KEY`, bound to the organization. Setup, the exact
  scopes and the Jira permissions: [JIRA.md](JIRA.md). `/api/integrations/jira`: `GET` (any domain role or
  the organization admin), `POST /authorizations`, `POST /connections`, `GET /options`, `PUT /settings`,
  `DELETE /connection` (the organization admin).
- **ServiceNow** has no connection: each ticket's `serviceNow` fields (`short_description`, `description`,
  `impact`, `urgency`, `cmdb_ci`, `due_date`) are for pasting, or for POSTing to `/api/now/table/incident`.
- Org-scoped (`risk_tickets` V23, `upgrade_proposals` V18/V27, with the RLS backstop); a response goes with
  its service. A proposal drafted on its own before responses keeps no ticket and is decided on its own.
- **In the UI**: **Draft response** on each row of the recommended upgrades and a service's vulnerable
  dependencies, and on each service of the change-impact page; `/responses/tickets/{id}` is the response —
  the decision, its pull request (edits, **Leave out**, **Try again**, **Draft upgrade pull request**) and
  the ticket exactly as it will be filed; `/responses/upgrades/{id}` shows a PR's full text. The review queue
  lists responses that still need a person and the history those decided.

## Regulator memos (E8 Story 6)

A regulator memo is a DORA (Regulation (EU) 2022/2554) notification about one change, drafted only when the
change **materially** affects a service. A vendor outage is drafted as the initial notification of a major
ICT-related incident (Article 19(1)); a vulnerability as a voluntary notification of a significant cyber
threat (Article 19(2)). Approving makes it final. **Aegis never sends it**: a person completes it and
submits it through the competent authority's own channel.

| Method | Path | Who | What |
|---|---|---|---|
| POST | `/api/actions/regulator-memos` | an analyst | Draft: `{changeSignalId}` → 201 `PROPOSED`; 409 when no service is materially affected |
| GET | `/api/actions/regulator-memos?changeSignalId=&status=A,B` | any domain role | Newest first |
| GET | `/api/actions/regulator-memos/{id}` | any domain role | Includes `body`, the whole memo in Markdown |
| POST | `/api/actions/regulator-memos/{id}/approve` | an analyst — **never the drafter** | → `APPROVED` (final, ready to download) |
| POST | `/api/actions/regulator-memos/{id}/dismiss` | an analyst (the drafter included) | `{reason}` → `DISMISSED` |

- **Templated** (`classpath:memo/dora-major-incident.md`, `dora-cyber-threat.md`), rendered in one pass so
  nothing a feed or the model wrote is read as a placeholder. Aegis fills in what it knows: a reference,
  when the organization became aware (an outage's start, or the vulnerability's publication), the initial
  notification's deadline (24 hours after detection), the affected services with their scores, the third
  party, the affected components and the evidence. The AI drafts the description, impact, measures and
  next steps (`memo`), or a plain fallback when it is unavailable. What Aegis can't know — the entity's
  name and LEI, the classification, clients, duration, member states, data losses — stays
  "[To be confirmed]" for a person; the model is told never to classify an incident as major itself.
- **Only analysts draft and decide** (`MemoRoles`): a memo covers every service a change affects, so it
  isn't one owner's to send. Compliance admins audit memos and service owners read them. One live memo
  per change. Org-scoped (`regulator_memos`, V25, with the RLS backstop).
- **In the UI**: the change-impact page shows a **Regulator notification (DORA)** panel when a service is
  materially affected; `/responses/memos/{id}` shows the timeline, the services and the whole memo, takes
  the decision, and downloads the final memo as Markdown.

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
  Org-scoped tables have row-level security, forced on their owner too: a role that isn't a superuser sees
  only the rows of the organization its transaction bound (`aegis.current_org`, bound by the stores; V9, read
  through `current_org()` since V31), and none when none is bound. Docker Compose connects as a superuser,
  which skips it; Amazon RDS has no superuser, so there it always applies, and the test suite connects as a
  plain role to match. A migration that backfills org-scoped rows must bind each organization itself, or it
  touches nothing where row-level security applies.
- **Scheduled jobs** — the feed polls, the normalisers and the OSV sync run on one instance at a time, the
  one holding the scheduler lease (`platform.scheduling.SchedulerLease`, V30). So rolling deploys and several
  instances are safe: the others stand by, and take over within a renewal when the holder stops, or once its
  lease expires if it dies.
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
| `IDENTITY_SERVICE_TOKEN` | *(empty)* | Token for identity-service's internal API (`X-Service-Token`), used only by the owner notifications; identity-service holds the same one as `IDENTITY_INTERNAL_SERVICE_TOKEN` |
| `AEGIS_NOTIFICATIONS_ENABLED` | `false` | Owner notifications (E9 story 5); needs `IDENTITY_SERVICE_TOKEN` |
| `AEGIS_NOTIFICATIONS_INTERVAL` | `PT5M` | How often the notification job looks for items that need a decision |
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
| `AEGIS_INGESTION_OSV_ENABLED` | `false` | Sync OSV's per-ecosystem exports ([OSV connector](#osv-connector-ecosystem-advisories)) |
| `AEGIS_OSV_ECOSYSTEMS` | `npm,PyPI,crates.io,RubyGems,Go,Maven` | Exact OSV bucket names to sync, comma-separated |
| `AEGIS_OSV_INCLUDE_MALICIOUS` | `false` | Also import OSV's malicious-package reports (`MAL-*`) |
| `AEGIS_OSV_POLL_INTERVAL` / `AEGIS_OSV_INITIAL_DELAY` | `P1D` / `PT30S` | How often the bulk sync runs, and how long after startup it first does |
| `AEGIS_OSV_NORMALIZE_INTERVAL` | `PT1M` | Cadence of the raw-event → advisory drain for OSV |
| `AEGIS_OSV_BASE_URL` | OSV's export bucket | Override the export host (a mirror, tests) |
| `AEGIS_SCHEDULING_POOL_SIZE` | `6` | Scheduler threads, so OSV's long sync never starves the other feeds' drains |
| `AEGIS_SCHEDULER_LEASE_DURATION` / `AEGIS_SCHEDULER_LEASE_RENEWAL` | `PT2M` / `PT30S` | How long the instance running the scheduled jobs holds the lease, and how often it renews it (V30) |
| `AEGIS_SBOM_GENERATION_ENABLED` | `true` | Import SBOMs from a service's linked repository (git + syft in the image) |
| `AEGIS_SBOM_ALLOWED_HOSTS` | `github.com,gitlab.com,bitbucket.org` | Hosts a repository may be linked on and cloned from — the SSRF boundary |
| `AEGIS_SBOM_CLONE_TIMEOUT` / `AEGIS_SBOM_SCAN_TIMEOUT` | `PT90S` / `PT180S` | Limit on getting the files (the partial clone, its sparse checkout and the checkout together) and on syft's scan |
| `AEGIS_SBOM_MAX_REPO_BYTES` / `AEGIS_SBOM_MAX_SBOM_BYTES` | 512 MiB / 64 MiB | Reject a checkout, or the SBOM syft writes, larger than this |
| `AEGIS_SBOM_GIT_PATH` / `AEGIS_SBOM_SYFT_PATH` | `git` / `syft` | The binaries SBOM generation runs |
| `AEGIS_GITHUB_*` | App disabled | The GitHub App (upgrade pull requests, private clones, Connect GitHub) — see [GITHUB_APP.md](GITHUB_APP.md). Its key is `AEGIS_GITHUB_APP_PRIVATE_KEY` (the PEM itself, as a secret manager hands it over) or `AEGIS_GITHUB_APP_PRIVATE_KEY_PATH` (a file) |
| `AEGIS_SCORING_*` | the calibrated model | Every scoring weight, the materiality threshold and the roll-up bounds — see [Configurable weights](#configurable-weights) |
| `TRACING_SAMPLE_RATE` | `1.0` | Share of requests traced to Zipkin |
| `AEGIS_JIRA_ENABLED` | `false` | File approved risk tickets in each organization's connected Jira; the other Jira variables are in [JIRA.md](JIRA.md) |
| `AEGIS_SECRETS_KEY` | *(empty)* | Base64 of 32 random bytes (`openssl rand -base64 32`) that seals stored integration credentials; required with Jira |

The vendor and news feed lists are YAML (`aegis.ingestion.vendor-status.vendors`,
`aegis.ingestion.news.feeds`), not env vars — each entry needs several fields.

## Evidence on every draft (E8 story 7)

Risk tickets, upgrade proposals and regulator memos retain the evidence available when they were drafted:
the canonical change and its feed reference, a source-text excerpt (up to 4,000 characters, with truncation
marked), its time and URL when supplied, the affected service and owner count, matching paths and confidence,
and the impact score, tier, materiality, arithmetic and configured factors. Missing source metadata is
explicit. The evidence remains available when AI drafting fails and Aegis uses its deterministic fallback.

Tickets persist it in `evidence` and include it when filed in Jira or copied as ServiceNow fields. Upgrade
proposals persist it in the reviewed PR body, up to 20,000 characters: a service reached through many
dependency paths keeps the first paths and the score and source lines, and the body says how many lines
in between are left out, so it stays within the 60,000 characters a draft PR may have.
Memos persist it in their body for review and Markdown download; the download also includes the AI's
server-resolved cited passages, quoted separately from the deterministic facts. Later changes to the estate
or feed do not recompute a stored draft's evidence. Existing drafts are not retrospectively enriched;
V32 only redacts historical owner-name lists as described below. Evidence exports and the decision audit
remain E10 work.

## Draft guardrails (E8 story 8)

Drafting creates only local `PROPOSED` responses. Filing a ticket and opening a GitHub **draft** PR
still require the existing human approval and role checks; Aegis has no merge operation. Regulator
memos become final on a second analyst's approval and are never sent. AI text cannot choose actions,
versions, scores, priorities or workflow states.

`platform.common.SensitiveText` redacts recognisable secrets and personal data from AI-port inputs
and outputs, draft narratives, evidence, citations, PR metadata, Jira blocks and ServiceNow fields.
Redaction precedes excerpt/title/citation truncation and Markdown escaping. Owner names and user IDs
are omitted from evidence and AI context; the assigned count is retained. `DraftEvidence` also removes
known owner display names embedded in matching explanations before persistence or an AI call. Private-key blocks,
credential-bearing URLs, Bearer/Basic credentials, common API/cloud/VCS token formats, JWTs,
labelled passwords/tokens, email addresses, SSNs, international phone numbers, and labelled personal
names/addresses/phone/identity fields are replaced with `[REDACTED]`. Each rule matches the shape of a
credential or contact detail and redacts only its value, so package specs (`lodash@4.17.21`, purls)
and prose such as "Basic authentication" or "CSRF token: the server…" reach drafts intact. Keep the
Java policy aligned with ai-service's `_guardrails.py` and their regression cases.

The AI service screens whole sources for recognisable control instructions before calling a
provider, then redacts and XML-escapes numbered passages. It validates its strict narrative schema,
rejects tool/function requests and selected false execution claims, and redacts generated text.
Citations are resolved only from those protected passages. A screened request or invalid AI output
uses the same deterministic fallback as an AI outage; source evidence and computed scores remain.
Memo interpolation is one pass, with feed/model text made inert as Markdown. Logs carry IDs and
exception types, never source text or model output.

These are layered safeguards, not general PII detection or a guarantee against every semantic
injection. Arbitrary unlabelled names/addresses, unknown or encoded secret formats and paraphrased
attacks can evade pattern matching. Raw ingestion/inventory storage is unchanged, and existing
repository file content is not rewritten for redaction. Text is redacted where it enters a draft (feed
text, evidence, AI output, dismissal reasons and upstream errors) and again at the AI, Jira and GitHub
ports; drafts stored before E8 story 8 are shown as stored. Identity fields needed for internal
authorization and auditing remain intact. The approval boundary applies regardless of model output.

### Historical owner evidence cleanup

Flyway migration `V32__redact_historical_draft_owner_evidence.sql` removes the owner-name list appended
by matching (`; owners: ...`) from stored ticket evidence, upgrade PR bodies and memo bodies of responses
that can still be decided or sent: `PROPOSED` ones, and `FAILED` tickets and PRs, which a retry files or
opens. A decided response (filed, opened, approved or dismissed), or one being filed or opened, keeps the
record its decision was made on. PR bodies drafted before V32 hold every explanation on one line; each
owner clause there is redacted on its own. It preserves dependency paths, possible-match warnings,
source references, scores, array order, creation and decision metadata, and statuses. It uses the
historical clause rather than
today's owner assignments, so renamed or removed owners are covered. Changed records receive an updated
timestamp and version; already protected records are unchanged. Forced tenant isolation is restored in
the same migration transaction, and the cleanup can be rerun without changing protected records.

The migration runs automatically when the updated core service starts. For an already running Compose
stack, rebuild and restart it from the project root:

```bash
docker compose up -d --build --no-deps aegis-core
```

This is a targeted cleanup of generated evidence clauses. It does not detect arbitrary unlabelled names
elsewhere in old AI narratives, rewrite repository contents, or edit previously filed Jira issues and
opened GitHub PRs. Any copies already published externally need separate review and cleanup.

Tests cover redaction formats, truncation boundaries, all AI draft shapes and citations, safe
provider failures, inert memo rendering, and fallback snapshot persistence. Database integration
tests require Docker; `PackageStructureArchTest` and the text/AI-port tests do not.

## Build & test

```bash
# from backend/aegis-core
./mvnw verify           # unit tests + integration tests (Testcontainers needs Docker) + format check
./mvnw spotless:apply   # fix import order / whitespace
mvn spring-boot:run     # run locally (needs Postgres on :5432 and identity-service on :8081)
```

In Docker Compose it builds and starts alongside identity-service and the ai-service. API docs at
`http://localhost:8082/swagger-ui.html`.

The integration tests connect to their Testcontainers PostgreSQL as a plain role (`aegis`, created by
`src/test/resources/testcontainers/app-role.sql`), not the container's superuser, so row-level security applies
to them as it does on RDS. A test about the schema itself, past those policies — a constraint, a migration's
backfill — uses `AbstractIntegrationTest.superuserDataSource()`.

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
and vendors remain intact.

**Each service has its own dependency graph (V29).** A library version's dependencies are resolved per
lockfile — two services on axios 1.18.0 can pin different follow-redirects — so the dependency edges an SBOM
declares are its service's (`business_service_component_dependencies`). The component dependency API
(`POST`/`DELETE /api/inventory/components/{componentId}/dependencies/{dependencyId}`) adds edges by hand,
which hold in every service; `GET …/dependencies` lists both kinds. A service's graph is the components it
links, its own edges and the hand-added ones, never another service's; `inventory.DependencyGraph` is the one
walk over it, behind matching, the upgrade recommendations and "Used by". Transitive reachability is resolved
at query time.

### Used by

- `GET /api/inventory/components/used-by?ecosystem=npm&name=axios` — every business service using the
  package, **in any version** the inventory holds, one row per service and version: `direct` when the
  service links that version itself, else `through` — the components from the one the service links down
  to the one depending on this version, the shortest way in that service's own graph — with owners. Sorted
  by service, then version (1.5.0 before 1.18.0). A package nothing uses, or the inventory doesn't hold,
  has no uses (not a 404); a blank ecosystem or name is a 400.
- `GET /api/inventory/vendors/{vendorId}/used-by` — every business service using the vendor, with its
  owners. Vendors are linked directly, so this is a join, not a walk; 404 when the vendor doesn't exist.

Both need the inventory's read role (`SERVICE_OWNER`, `ANALYST`, `COMPLIANCE_ADMIN`). In the UI, each row of
the Components and Vendors pages opens its **Used by**.

### SBOM import (CycloneDX)

**From the linked repository** — what the UI does. `POST /api/inventory/services/{serviceId}/sbom/from-repository`
(`ANALYST`, `COMPLIANCE_ADMIN`, or the service's assigned owner; 409 until a repository is linked)
clones the service's own linked repository at its branch, runs syft over it, and imports the
result. The clone fetches only what syft can read: a shallow partial clone brings the file tree without
contents, then a sparse checkout fetches every file except images, video, audio, fonts and documents, in one
batch — an application's assets are most of its weight (one repository was 21 MB of images around 660 KB of
manifests, and a full clone outran the 90-second clone timeout on a slow link). The body is optional, `{"token": "…", "username": "…"}`. Access, first that applies: a pasted
read-only **token** (used for this clone only, never stored; the username defaults to the host's
convention — GitHub ignores it, GitLab `oauth2`, Bitbucket `x-token-auth` for access tokens and
`x-bitbucket-api-token-auth` for `ATATT…` API tokens); a **host integration** — the GitHub App mints a
read-only installation token when it is installed on the repository's account and that account is
bound to the caller's organization ([GITHUB_APP.md](GITHUB_APP.md)); otherwise **none**, which is all a
public repository needs. A refusal says which it was and what to do. Any host on
`AEGIS_SBOM_ALLOWED_HOSTS` works: add a self-hosted GitLab, Gitea or Bitbucket Data Center host there.
git gives up on a transfer slower than 1 KB/s for 30 seconds rather than waiting out the clone timeout.

**A repository's own packages are the repository.** syft scans a directory, so it reports a Maven module
(found at its own `pom.xml`) or an npm `package-lock.json`'s root project as components, with the libraries
they declare below them — taken literally, every library would look transitive and nothing could ever be
upgraded, and two repositories' private `client@0.0.0` would share coordinates. A repository import
(`inventory.sbom.RepositoryLayout`) removes those project packages, makes what they declare the service's
direct dependencies, and records on each link every file that declares it (`business_service_components.
manifest_paths`, **V26**, replacing V20's single path: `client/package.json`, `backend/aegis-core/pom.xml`; the
`package.json` beside a pnpm/yarn lockfile; and a file Aegis doesn't edit as itself — `app/gradle.lockfile`,
`poetry.lock` — so an upgrade never guesses a manifest instead). The same version declared in two projects is one component that syft
reports with a location for each, so its link lists both, and a response's upgrade pull request edits every
one of them. Services imported before V26 keep the one manifest V20 recorded; re-import them.

**A JavaScript lockfile says what is declared (V39).** syft reports every package a lockfile pins, but not which
ones the project itself declares, and it drops optional dependencies from the graph (npm and pnpm): taken
literally, any package nothing depended on looked declared — `sharp` and every platform binary next pulls in
optionally among them, whose upgrades then failed when the PR opened — and a declared package that another one
also depends on (react, under next) looked transitive. So the import reads each lockfile in the same checkout
(`inventory.sbom.JsLockfiles`: npm lockfile versions 1–3, pnpm 5–9 with workspaces, yarn classic and berry): a
package is direct when one of the lockfile's projects declares it, recorded with that project's `package.json`,
and the lockfile's own edges, optional ones included, hang the rest below what pulls them in. Each direct
dependency also records the lockfiles resolving it (`business_service_components.lockfile_paths`), which is where an
override of what it pulls in belongs. A lockfile that can't be read leaves its packages as syft's graph says.
Services imported before V39 keep their old links until re-imported.

**Where the code imports each dependency (E11-3, V40).** The same import reads the checkout's code for the modules it
imports (`inventory.sbom.ImportScanner`): JavaScript and TypeScript `import`, `export … from`, `import()` and
`require()`; Python `import` and `from … import`; Java and Kotlin `import`. It reads source text and never runs it.
Comments are blanked first, across lines (a `/* … */` block, a Python docstring), so a commented-out import never
counts, and a Python statement is read whole (a backslash continuation, a parenthesised `from x import (…)`). Each
lowercase name of `from x import y` is recorded as `x.y`, the submodule it may be (`from google.cloud import storage` is
`google.cloud.storage`, which google-cloud-storage is imported as), except from the repository's own Python packages,
whose names are its own (only `x`, so they don't fill the scan's 5,000 modules). It
skips `node_modules`, virtual environments, build output and minified bundles, symbolic links (on disk, and those the
repository records as links, from `git ls-files`), and files whose names look like they hold secrets; and stops after
20,000 files or 128 MB, skipping a file over 1 MB, saying so (`import_scan_limit`). Relative imports and the languages'
standard libraries are left out. Each module imported is stored per service (`service_imports`, org-scoped with RLS:
the module, how many imports, the first twenty `path:line`), and the scan itself on the service — when, the commit
(`git rev-parse HEAD`, for links), the languages it read files of, how many files, why it stopped early
(`importScan` in the service response). Module names, paths and line numbers only, never code; a change of repository
link forgets them. `inventory.CodeUsage` answers, per dependency, `IMPORTED` (with each line, linked at the commit read
in the host's web view), `NOT_IMPORTED`, or `UNKNOWN` with why. A package's import names come from `inventory.ImportNames`:
an npm package by its own name; a Python distribution through a table of the ones imported under another name (PyYAML
is `yaml`, Pillow `PIL`) and common ones, else its conventional name; a Maven artifact through a table of its Java
packages (Guava is `com.google.common`), else its group id. Not imported needs a name Aegis is sure of, in code it read
completely: a guessed name, a library frameworks load without an import (a logging backend, a JDBC driver), only a
Python package above the name seen (`import google.cloud` for `google.cloud.storage`), a scan that stopped early, an ecosystem it doesn't read, a language with no file read, a dependency linked by hand or by an upload,
or a service never imported from its repository are all unknown — never shown as unused. The recommendations carry it
as `usage` (and `viaUsage`, for what pulls a transitive one in), in one lookup for every service listed; a line
importing several of a package's modules counts once. A version syft could
not resolve (`UNKNOWN`, a parent-managed Maven version) is skipped rather than imported.

**A re-import follows the repository (V28).** An import from the repository reads everything it declares, so it
unlinks the direct dependencies an earlier import from it linked that it no longer declares — an upgraded
library's old version, which otherwise stayed linked beside the new one and was reported as vulnerable for good —
and any project packages an earlier import linked (`serviceComponentsUnlinked` in the response). A component
linked by hand or by an uploaded SBOM stays linked (`business_service_components.from_repository`, set on every
link an import from the repository makes or finds; backfilled for links with a recorded manifest), and
components stay in the inventory. The repository's word is its lockfile: syft reads the versions a lockfile
pins, so an upgrade counts once the lockfile is refreshed, not when `package.json` alone changes.

Every import is recorded on the service (**V19**): `sbomImportedAt` and `sbomImportSource` — `UPLOAD`,
`PUBLIC`, `INTEGRATION` or `TOKEN`, never the credential — in every service response, and cleared when the
repository link points elsewhere or is removed. The service page shows it ("SBOM last imported … · with an
access token") and, when the last import needed a token, asks for one before importing rather than after
a failure. In the UI, linking a repository imports straight away; afterwards a single **Import SBOM**
dialog, pre-filled with the saved repository, imports again, changes the URL or branch first, or unlinks.

**Uploading a file** — for automation such as a CI pipeline; the UI no longer offers it.
`POST /api/inventory/services/{serviceId}/sbom` (same roles) takes a CycloneDX JSON
SBOM (`application/json` or `application/vnd.cyclonedx+json`). Either way, the SBOM merges into the map:

- Components are matched by (ecosystem, name, version), case-insensitively, or created. The ecosystem
  is the purl type and the name follows advisory-feed spelling (`group:artifact` for Maven,
  `@scope/name` for npm); components without a purl use the `generic` ecosystem. Components with no
  version are skipped; nested components are included.
- `dependencies` become the service's own dependency edges (V29, above). The service is linked only to its
  top-level components (the SBOM root's direct dependencies, plus any component nothing else depends on), so
  deeper libraries are reported as transitive impact.
- An upload only adds; an import from the repository also unlinks what the repository no longer declares
  (above) and removes the dependency edges it no longer declares — a transitive version the lockfile moved
  away from (`dependenciesRemoved`). Either way the import is idempotent and atomic — a malformed purl, oversized field, or more than
  5,000 components rejects the whole document with a 400. The response counts what changed.

### Demo estate

A fictional estate — five services, their vendors, and four CycloneDX SBOMs (Maven, npm,
PyPI) — lives in `src/main/resources/demo/`. Set `AEGIS_DEMO_SEED_ENABLED=true` (Docker Compose does)
to load it at startup through the SBOM import; seeding is idempotent. Open **Used by** on
`org.apache.logging.log4j:log4j-core 2.14.1` under Components: Order Service uses it directly, Payments API
reaches it through three hops. The seed assigns **no owners** — an owner is a verified organization member, and
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
- **One instance at a time** — every scheduled job first asks the `SchedulerLease` (V30), so with several
  aegis-core instances only the lease holder polls and drains.
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

### OSV connector (ecosystem advisories)

[OSV](https://osv.dev) aggregates the ecosystem databases — GHSA, PyPA, RustSec, RubySec, Go — into one
schema, so one connector brings advisories that NVD's CPEs miss (an npm package is often advised only in
GHSA). Each advisory's affected products are **purl** coordinates (ecosystem + package, V11), and its
`canonical_id` is the CVE it aliases when it has one, so NVD's and OSV's records of one CVE are a single
change signal (ADR §6a).

- **Bulk sync, self-scheduled.** OSV publishes a per-ecosystem export (hundreds of MB), not a windowed feed,
  so the connector runs on its own schedule (`AEGIS_OSV_POLL_INTERVAL`, daily) instead of the 15-minute poll
  loop, and streams each record straight into the raw-event store (constant memory). The raw-event id is
  `{osvId}@{sha256 prefix of the record}`, so a revised record re-normalises and an unchanged one
  de-duplicates.
- **Off by default** like every feed: `AEGIS_INGESTION_OSV_ENABLED=true`, and `AEGIS_OSV_ECOSYSTEMS` with
  OSV's exact bucket names. Malicious-package reports (`MAL-*`, most of npm's records) are skipped unless
  `AEGIS_OSV_INCLUDE_MALICIOUS=true`: they are malware reports, not vulnerabilities.
- The scheduler pool (`AEGIS_SCHEDULING_POOL_SIZE`) is widened so a long sync runs beside the other feeds'
  drains rather than starving them.

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
of its own idempotently-upserted table. A vulnerability reported by several feeds (NVD, OSV) appears once,
as its most recently revised advisory: V21 records that row as the advisory's `representative`, kept right
by a trigger on every write, so a lookup by id or CVE is an index hit rather than a de-duplication of every
advisory (about 100 ms → 0.1 ms per lookup at 46k advisories).

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
- The reverse dependency graph is traversed breadth-first from each affected component — **each service's
  own graph** (`inventory.DependencyGraph`, V29): the edges its SBOMs declared plus those added by hand, never
  another service's resolution of the same library. The response groups results by service and gives each
  reason the shortest path in that service's graph from the vulnerable component to a component the service
  uses directly (one reason per such component), followed by that service's owners. Cycles terminate and do
  not duplicate a service.
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
score = severity x exposure x reachability x criticality x 100
```

Multipliers in `[0,1]`, so the score is a 0-100 percentage of the worst case and a service that
is weak on any one axis cannot be pushed to the top by the others. Same read roles as matching
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
- **reachability** (E11-4) — whether the service's code reaches the vulnerable package, from where its
  code imports packages (E11-3, `inventory.CodeUsage`) and its own dependency graph
  (`inventory.Reachability`): `IMPORTED` when the code imports the package; `NOT_IMPORTED` only when it
  imports neither the package nor anything above it in the service's graph (every package that pulls it
  in, directly or through others; a cycle ends), each of them not imported by CodeUsage's rule of
  evidence; `UNKNOWN` otherwise — the code imports what pulls it in, or that is unknown, or the package's
  own imports are (the code wasn't read, a guessed import name, a scan that stopped early). Only
  `NOT_IMPORTED` discounts (0.8: a band lower at most); `UNKNOWN` scores like `IMPORTED`, so no score
  drops without evidence. A change matching several of a service's components takes the most reachable
  of them. Like exposure it applies to vulnerabilities only (`reachabilityApplies: false` for an outage).
  Each scored service carries the verdict as `reachability` — `status`, `reason`, `through` (the package
  pulling it in that the verdict rests on, if any) and `usage` (that package's imports, with each line
  linked at the commit read) — and so does each upgrade recommendation, which the triage queue and each
  service's vulnerable dependencies rank by. Drafts state the verdict and its reason, never the files.
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
`SEVERITY`, `EXPOSURE`, `REACHABILITY`, `CRITICALITY`, `CONFIDENCE`, so a client can render "why this number" without
knowing the model's shape.

| Field | Meaning |
|---|---|
| `factor` | Which term this is |
| `input` | What the multiplier was read from: `CVSS 3.1 base 9.8`, `severity major`, `INTERNET_FACING`, `NOT_IMPORTED`, `HIGH`, `CONFIRMED`, … |
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
The change-impact page in the frontend (`/advisories/{id}`, and the item detail `/findings/{id}`) shows the
score, tier, and factors on each impacted service, with the files and lines the reachability factor rests on
under its row (linked at the commit read), or why nothing reaches the package, or why that's unknown.

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
| `aegis.scoring.reachability.*` | `imported 1.0 / not-imported 0.8` | What a vulnerability is worth when the code never imports the package nor anything pulling it in; unknown always takes `imported`'s weight (a configured `unknown` is ignored), and `not-imported` is held at or below `imported` |
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
