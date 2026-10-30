# Aegis data classification

Every kind of data Aegis ingests, stores, generates or sends out: its class, where it is kept, who can read it, how
long it is kept, and what leaves Aegis. Checked against the public privacy notice (`/privacy`) at the end.
Companion to [threat-model.md](threat-model.md); story CS203-146 (E12-2). As of `develop` on 2026-10-23.

## Classes

| Class | Meaning | Handling |
|---|---|---|
| **Public** | Published by someone else; disclosure harms no one | Treated as untrusted input, never as instructions (threat model B6.2, B9) |
| **Confidential** | One organization's own data: its estate, its weaknesses, its decisions | Visible only inside that organization: store-level scoping plus row-level security (threat model B5.1); domain roles only |
| **Personal** | Identifies a person: email address, name, IP address, user agent, user id | Confidential handling, plus: never sent to the AI provider, and retention decided explicitly (below) |
| **Secret** | Grants access: passwords, tokens, keys | Hashed or encrypted at rest, or never stored; values in SSM, never in the repository or Terraform state |

"Domain roles" means `SERVICE_OWNER`, `ANALYST` and `COMPLIANCE_ADMIN` in the organization (`InventoryRoles.READ`).

## What Aegis holds

### Ingested from outside

| Data | Class | Where it's kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Advisories (NVD, OSV), vendor status incidents, security news, and the raw events they were parsed from | Public | aegis-core: `advisories`, `advisory_affected_products`, `vendor_incidents`, `news_items`, `news_item_cve_references`, `raw_events` — global, not per organization | Any domain role, through the advisory and change-signal views | No limit | The AI provider (descriptions, redacted); quoted in tickets, pull requests and emails as inert text |
| A fix commit's diff (when an advisory names one) | Public | Not stored: fetched for an AI assessment; a summary of the changed files and function names is cached in memory (`FixCommits`) | aegis-core only | Until the process restarts | The AI provider (the summary, ≤ 1,200 characters) |

### An organization's estate

| Data | Class | Where it's kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Services (name, criticality, exposure, repository link), components, vendors, dependency edges | Confidential | aegis-core inventory tables, per organization with row-level security | The organization's domain roles | Until the organization deletes it (a service's rows go with it) | Service and package names, versions and vendors: the AI provider (drafts), Jira (filed tickets), GitHub (pull requests) |
| From each SBOM import: packages, versions, the manifests and lockfiles declaring them | Confidential | `business_service_components`, `software_components`, `business_service_component_dependencies` | Domain roles | Replaced by the next import; removed with the service | As above |
| Where the code imports each package: module, count, up to 20 `path:line` sites; the scan's commit and languages | Confidential | `service_imports`, `business_services` | Domain roles | Replaced by the next import; removed with the service | Nowhere (the paths are linked in the app only) |
| The repository's code | Confidential | **Never stored**: a temporary checkout for an import or an assessment, always deleted (`RepoSbomGenerator`, `RepositoryCode`) | aegis-core only | Minutes | The AI provider, for an assessment only: up to 6 excerpts of ±3 lines with their file paths, redacted (threat model B6) |
| Service owners: user id, display name, email snapshot | Personal | aegis-core `owners`, `business_service_owners` | Domain roles (the owners directory) | While they own a service (an owner with none is deleted) | Never to the AI provider (drafts say how many owners, not who) |

### Responses, decisions and history

| Data | Class | Where it's kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Risk tickets: the AI's narrative, Aegis's evidence, edits and overrides with reasons, decisions | Confidential | `risk_tickets` | Domain roles; drafted and decided by analysts and the service's owners | No limit | Jira, when approved (title, description, labels, priority, due date) |
| Upgrade pull requests: edits, title and body | Confidential | `upgrade_proposals` | Domain roles | No limit | GitHub, when approved, as a draft pull request |
| DORA regulator memos | Confidential | `regulator_memos` | Domain roles; drafted and decided by analysts | No limit | Nowhere automatically: a person downloads it once final and submits it |
| AI reachability answers: verdict, rationale, checks, the terms searched, cited paths and lines — never the code | Confidential | `reachability_assessments` | Domain roles | No limit (keyed by the commit read) | The verdict, rationale and checks into a drafted ticket's evidence, so to Jira |
| The decision trail: every step a response took, who took it (user id), when, why, and the response as it stood | Confidential, Personal | `decision_records`, append-only | `ANALYST`, `COMPLIANCE_ADMIN` | No limit; the application can't delete it | Evidence exports (below) |
| Evidence exports: a service's decisions for a period, people named from the member directory | Confidential, Personal | Not stored: a temporary file while it downloads, then deleted (`EvidenceExport`) | `ANALYST`, `COMPLIANCE_ADMIN` | The download | The downloading person, as a PDF or CSV |
| Owner notifications: owner (user id), the item's title, service name, score and tier, read and email status | Confidential, Personal | `owner_notifications` | Only the owner it is for | No limit (removed with the service) | identity-service, which emails it (below) |
| Dashboard history: daily counts per organization, service and owner, with service names and owners' display names as they were that day | Confidential, Personal | `risk_posture_snapshots`, `risk_posture_breakdowns` | Domain roles | No limit; kept after an owner leaves or a service is deleted | Nowhere |

### Accounts and sign-in (identity-service)

| Data | Class | Where it's kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Account: email, display name, enabled, email verified, last active organization | Personal | `users` | The user; members of their organizations (the read-only directory); org admins; platform admins | Until the account is deleted | Amazon SES, to email the user |
| Password | Secret | `users`, as a bcrypt hash | No one | The account's life | Nowhere |
| Sessions: access token (JWT, 15 minutes), refresh token (7 days, single-use) | Secret | The browser's httpOnly cookies; refresh tokens as SHA-256 hashes in `refresh_tokens` | No one (verified only) | Until expiry or revocation; expired rows stay | Nowhere |
| Second factors: TOTP secret, passkey (WebAuthn credential id and public key), recovery codes, emailed login codes | Secret | `user_totp` (AES-256-GCM), `webauthn_credentials`, `mfa_recovery_codes` and `login_challenges` (SHA-256 hashes) | No one (verified only) | Until removed or used; login codes expire | Login codes: SES, to the user |
| Account links: verify email, reset password, invitation | Secret | `auth_tokens`, as SHA-256 hashes, expiring | No one | Until used or expired | SES, to the user |
| Organizations, memberships and their roles | Confidential | `organizations`, `organization_members`, `organization_member_roles`, `user_roles` | Members (their own organizations); org and platform admins | Until removed | aegis-core reads the active organization and roles from each token |
| Security log: every sign-in, MFA step and account change, with email, IP address and user agent | Personal | `audit_events`, append-only | Platform admins | No limit; outlives a deleted account | Nowhere |
| Owner-notification emails sent: delivery id and time | Internal | `owner_email_deliveries` | No one (deduplication only) | No limit | Nowhere |

### Credentials Aegis uses

| Data | Class | Where it's kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Jira access and refresh tokens; the Jira site's id, name and address, project and issue type | Secret (tokens), Confidential | `jira_connections`, tokens sealed with AES-256-GCM bound to the organization (`SecretBox`) | aegis-core only; the site and settings by the organization | Until an org admin disconnects (deleted at once) | Atlassian |
| GitHub App installation: id, account name, id and type, who connected it | Confidential | `github_connections` | The organization's admins and domain roles | Until disconnected | — |
| GitHub installation tokens | Secret | Memory only, minted per job, one repository, one hour | aegis-core only | An hour | GitHub |
| A GitHub sign-in token (when connecting), a token pasted for an import | Secret | Never stored; the sign-in token is revoked after the check | aegis-core only | The request | GitHub, the repository host |
| Pending connections (OAuth state) | Secret | `github_connect_states`, `jira_connect_states` | aegis-core only | 10 minutes, single-use | — |
| Deployment secrets: JWT signing key, TOTP and integration encryption keys, service tokens, database passwords, SMTP login, GitHub App key and secret, Jira client secret, Groq and NVD API keys | Secret | SSM SecureString parameters (AWS); `.env` (git-ignored) or documented placeholders locally | The services that use them, at start | Until rotated | Each only to its own provider (the Groq key to Groq, and so on) |

### Operations

| Data | Class | Where it's kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Container logs (may contain user ids and request paths; the redaction code never logs what it redacts) | Internal, may be Personal | CloudWatch Logs | The AWS account's operators | 14 days | — |
| Traces and metrics | Internal | Zipkin and Prometheus locally; tracing is off in AWS (`TRACING_SAMPLE_RATE=0`) | Operators | Local only | — |
| Database backups | All of the above | RDS automated backups, encrypted | The AWS account's operators | 7 days | — |

### Operational monitoring (CS203-151/152)

| Data | Class | Where it's kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Feed counters/health, pipeline histograms, aggregate Review & approve depth, HTTP rates and alert states | Internal operational data; queue totals are confidential | Prometheus `prometheus-data` volume; dashboard definitions in Git, Grafana settings in `grafana-data` | Deployment operators; Grafana login on loopback, backend scrape endpoints on the existing operator network | Prometheus: 15 days; Grafana settings until its volume is removed | Local Prometheus/Grafana only; no external notification receiver configured |

New labels contain no organization/service/owner/repository identifiers, personal data, source text or response
content. Queue collection uses the existing internal organization directory and each org's RLS-scoped count
queries; the only exported value is a global total and its refresh timestamp. The AI network stays isolated.
Monitoring services need operator access controls before remote hosting (threat model B12).

## What leaves Aegis

| Destination | What | When | Safeguards |
|---|---|---|---|
| LLM provider (Groq, only with `LLM_PROVIDER=groq`; the default stub sends nothing) | Published change descriptions; Aegis's facts (services, packages, versions, vendors, scores, the number of owners); for assessments, a fix summary, search counts and up to 6 code excerpts with paths | Drafting a response or memo; asking for, or drafting a ticket that triggers, an assessment | Redacted on both sides, no personal data or repository address, budgets (threat model B6) |
| Amazon SES (from identity-service) | Account emails (verification, sign-in codes, password reset, invitations); owner notifications: the item's title, service name and a link into the app | Account flows; the notification job | Sent only to the account's own verified address; notification links are the app's own (B7.2) |
| Jira (the organization's site) | A risk ticket's title, description, labels, priority, due date | When a person approves it | Approval gate; text-only descriptions (B6.4) |
| GitHub | Clones and file reads of linked repositories; on approval, a commit to an `aegis/` branch and a draft pull request | Imports, assessments, approvals | Installation tokens per job (B8.2); drafts only, never merged (B8.3) |
| Feed publishers (NVD, OSV, vendor status pages, news) and GitHub/GitLab for fix commits | Requests only, plus the NVD API key to NVD | Scheduled polling; assessments | No internal credentials sent to publishers (B9.1) |
| A person's download | Evidence exports (PDF or CSV); final regulator memos (Markdown) | When they ask | Role-gated; memos are never sent by Aegis |

## Checked against `/privacy`

The notice (`frontend/src/app/(marketing)/privacy/page.tsx`) covers Jira, GitHub, AI drafting, the AI reachability
assessment, dashboard history and the separation of organizations, and on those it matches this classification.
It doesn't yet cover:

| Gap | Owner | Follow-up |
|---|---|---|
| Accounts: what identity-service keeps (email, name, password hash, second factors), the emails it sends through Amazon SES, and the security log (email, IP address, user agent; append-only, outliving a deleted account) | Frontend | Already planned as "Privacy notice at sign-up": add an Account section and link the notice from sign-up |
| Owner notifications: kept in the app with no limit, and emailed (title, service, link) | Frontend | Add to the notice |
| The decision trail: who decided what, with reasons, kept with no limit and impossible to delete in the app; evidence exports that name people | Frontend | Add to the notice |
| Service owners' name and email snapshot in aegis-core | Frontend | Add to the notice |
| The AI provider isn't named, nor its data policy linked | Security | Name the provider and link its policy (threat model B6.9) |
| Log and backup retention (14 and 7 days) | Frontend | Add to "Where it is kept" |

## Retention decisions

Several stores keep data with no limit. Recorded 2026-10-23 for CS203-146 as accepted until prod holds customer
data, when each gets a retention period and a job that enforces it:

| Store | Decision | Owner |
|---|---|---|
| `decision_records` | Keep with no limit: it is the audit trail a reviewer or regulator asks for. Revisit before prod: a fixed period agreed with whoever runs the deployment. | Core |
| `risk_posture_snapshots`, `risk_posture_breakdowns` (owners' names) | Keep with no limit for now, as the privacy notice says. Revisit before prod: a period, or dropping names after it. | Core |
| `audit_events` (email, IP, user agent) | Keep with no limit for now. Revisit before prod: a period (for example 1 year). | Identity |
| `owner_notifications`, `risk_tickets`, `upgrade_proposals`, `regulator_memos`, `reachability_assessments` | Keep with no limit for now; removed with their service. Revisit before prod. | Core |
| Expired `refresh_tokens`, `auth_tokens`, `login_challenges` rows | Keep (they are hashes and grant nothing once expired). Revisit: a clean-up job. | Identity |
| Feed tables and `raw_events` | Keep with no limit (public data). Revisit if storage grows. | Core |

### Pipeline trace correlation (CS203-153)

| Data | Class | Where it is kept | Who reads it | Kept for | Leaves Aegis to |
|---|---|---|---|---|---|
| Trace/span IDs, raw-event/change-signal/service UUIDs, stage names, error types, durations, AI route templates and HTTP statuses | Internal operational data; service UUIDs are tenant-linked | Local Zipkin and container logs; AWS container logs under existing settings | Deployment operators | Local Zipkin uses its default in-memory storage (lost on restart); AWS logs retain 14 days | Local Zipkin collector only; no AI provider receives these added fields |

The AI container can reach Zipkin on `internal`; it still has no public host port or gateway
route and does not join `edge`. New pipeline tracing records no request/response body, feed
text, prompts, credentials, personal names, or exception messages. Correlation UUIDs are not
metric labels. Incoming trace IDs are diagnostic metadata, never identity or authorization.
This change enables AI tracing only in local Compose, not Terraform.
