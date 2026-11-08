# Access control

Every endpoint, and who may call it — the table the access tests check (CS203-147, E12-3). A pull request that
adds an endpoint, or changes who may call one, changes this file in the same pull request; the tests fail
otherwise.

- **Tests:** `GatewayAccessTest` (gateway), `IdentityAccessIntegrationTest` (identity-service) and
  `AccessControlIntegrationTest` (aegis-core) read the tables below. Each calls every endpoint anonymously and as
  each role, and checks the answer: **401** without a token where one is needed, **403** for a role that may not
  call it, and anything else — the endpoint's own answer — for one that may. Each also lists the service's real
  endpoints and fails on one this file doesn't have, or on a row that matches no endpoint.
- **How a test calls:** with a token carrying one role (and, for the organization roles, an `org` claim; a
  platform admin has none, as in production), random ids in the path and a valid body, so it reaches the
  authorization check rather than stopping at validation. The tests hand the services an already-decoded token,
  so they test the filter chains and method security, not the signature (that check is threat model B3.1).
- **`own` and `self`:** both are checked as refused for what isn't the caller's, and `self` as allowed on the
  caller's own account too. The allowed side of `own` (a service owner on their own service) needs real records,
  so each feature's integration tests cover it (for example `RiskTicketIntegrationTest`,
  `UpgradeProposalIntegrationTest`, `ReachabilityAssessmentIntegrationTest`).
- **The service token** (identity-service's internal API) is checked to open the internal API and nothing else:
  on any other protected endpoint it counts as no token.
- **CI:** changing this file reruns the three services' workflows.

## Legend

| Value | Meaning |
|---|---|
| `yes` | Allowed: the request reaches the endpoint |
| `no` | Refused: 401 without a token, 403 with a token of this role |
| `own` | A service owner, only for a service they are assigned to (`@serviceOwnership`, `@riskTicketAccess`, `@upgradeProposalAccess`); refused (403) for any other |
| `self` | Only for your own account (`#id == authentication.name`); refused (403) for anyone else's |

Roles: `USER` (every account), the three domain roles `SERVICE_OWNER`, `ANALYST` and `COMPLIANCE_ADMIN`,
`ORG_ADMIN` (an organization's admin) and `PLATFORM_ADMIN` (the operator, in no organization).

## Gateway

The gateway authenticates and routes; the services decide the rest. Each row is a route the gateway serves; a path
no row matches is refused without a token and answers 404 with one.

| Method | Path | Anonymous | USER | SERVICE_OWNER | ANALYST | COMPLIANCE_ADMIN | ORG_ADMIN | PLATFORM_ADMIN | Routed to |
|---|---|---|---|---|---|---|---|---|---|
| any | `/auth/**` | yes | yes | yes | yes | yes | yes | yes | identity-service, which requires a token for its own non-public `/auth` endpoints |
| any | `/oauth2/**` | yes | yes | yes | yes | yes | yes | yes | identity-service |
| any | `/users/**` | no | yes | yes | yes | yes | yes | yes | identity-service |
| any | `/orgs/**` | no | yes | yes | yes | yes | yes | yes | identity-service |
| any | `/api/admin/**` | no | no | no | no | no | no | yes | aegis-core |
| any | `/api/**` | no | yes | yes | yes | yes | yes | yes | aegis-core |
| GET | `/actuator/health` | yes | yes | yes | yes | yes | yes | yes | the gateway itself |
| GET | `/actuator/info` | yes | yes | yes | yes | yes | yes | yes | the gateway itself |
| GET | `/actuator/prometheus` | yes | yes | yes | yes | yes | yes | yes | the gateway itself |
| any | `/internal/**` | no | yes | yes | yes | yes | yes | yes | nothing (404): identity-service's internal API is never routed |

## identity-service

Decided mostly by its filter chains (`config/SecurityConfig.java`, `internal/InternalSecurityConfig.java`), and by
`@PreAuthorize` on `UserController` and `AuditController`. The service-token column is the internal API's
`X-Service-Token`.

| Method | Path | Anonymous | USER | SERVICE_OWNER | ANALYST | COMPLIANCE_ADMIN | ORG_ADMIN | PLATFORM_ADMIN | Service token | Rule |
|---|---|---|---|---|---|---|---|---|---|---|
| GET | `/auth/config` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/register` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/login` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/login/select-method` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/login/verify-otp` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/login/webauthn/options` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/login/webauthn/verify` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/refresh` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/logout` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/verify-email` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/resend-verification` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/forgot-password` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/reset-password` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/accept-invite` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| GET | `/oauth2/jwks` | yes | yes | yes | yes | yes | yes | yes | no | public (`SecurityConfig`) |
| POST | `/auth/logout-all` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/switch-org` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/change-password` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| GET | `/auth/mfa/status` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/mfa/totp/init` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/mfa/totp/confirm` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/mfa/totp/disable` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/mfa/recovery-codes/regenerate` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| GET | `/auth/mfa/webauthn/credentials` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| DELETE | `/auth/mfa/webauthn/credentials/{id}` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/mfa/webauthn/register/begin` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| POST | `/auth/mfa/webauthn/register/finish` | no | yes | yes | yes | yes | yes | yes | no | authenticated; acts on your own account |
| GET | `/orgs` | no | yes | yes | yes | yes | yes | yes | no | authenticated; your own organizations |
| POST | `/orgs` | no | yes | yes | yes | yes | yes | yes | no | authenticated; creates an organization you administer (`ORG_SIGNUP_ENABLED`) |
| GET | `/users` | no | yes | yes | yes | yes | yes | yes | no | authenticated; the read-only directory of your active organization |
| GET | `/users/me` | no | yes | yes | yes | yes | yes | yes | no | authenticated; the read-only directory of your active organization |
| GET | `/users/{id}` | no | yes | yes | yes | yes | yes | yes | no | authenticated; the read-only directory of your active organization |
| POST | `/users` | no | no | no | no | no | yes | yes | no | `MANAGE`: org admins in their organization, platform admins |
| DELETE | `/users/{id}` | no | no | no | no | no | yes | yes | no | `MANAGE`: org admins in their organization, platform admins |
| PUT | `/users/{id}/roles` | no | no | no | no | no | yes | yes | no | `MANAGE`: org admins in their organization, platform admins |
| PUT | `/users/{id}` | no | self | self | self | self | yes | yes | no | `MANAGE`, or your own profile |
| GET | `/audit` | no | no | no | no | no | no | yes | no | `PLATFORM_ADMIN`; the gateway doesn't route it, so it is reachable only inside the network |
| GET | `/internal/organizations` | no | no | no | no | no | no | no | yes | `X-Service-Token` only, its own filter chain (`InternalSecurityConfig`); never routed by the gateway |
| POST | `/internal/notifications/owner-email` | no | no | no | no | no | no | no | yes | `X-Service-Token` only, its own filter chain (`InternalSecurityConfig`); never routed by the gateway |

## aegis-core

Every endpoint needs a token (`platform/config/SecurityConfig.java`), and each handler's `@PreAuthorize` decides
which roles (`InventoryRoles`, `TicketRoles`, `UpgradeRoles`, `MemoRoles`, `AssessRoles` and the controllers' own
`READ`/`MANAGE`). Organization-scoped data is then limited to the token's organization (`CurrentOrg`, row-level
security). The OpenAPI docs and the health, info and Prometheus endpoints are public and aren't listed.

| Method | Path | Anonymous | USER | SERVICE_OWNER | ANALYST | COMPLIANCE_ADMIN | ORG_ADMIN | PLATFORM_ADMIN | Rule |
|---|---|---|---|---|---|---|---|---|---|
| GET | `/api/actions/reachability-assessments` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/actions/reachability-assessments` | no | no | own | yes | no | no | no | `AssessRoles.ASK` |
| GET | `/api/actions/regulator-memos` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/actions/regulator-memos` | no | no | no | yes | no | no | no | `MemoRoles.ANALYST` |
| GET | `/api/actions/regulator-memos/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/actions/regulator-memos/{id}/approve` | no | no | no | yes | no | no | no | `MemoRoles.ANALYST` |
| POST | `/api/actions/regulator-memos/{id}/dismiss` | no | no | no | yes | no | no | no | `MemoRoles.ANALYST` |
| GET | `/api/actions/risk-tickets` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/actions/risk-tickets` | no | no | own | yes | no | no | no | `TicketRoles.PROPOSE` |
| GET | `/api/actions/risk-tickets/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/actions/risk-tickets/{id}/approve` | no | no | own | yes | no | no | no | `TicketRoles.DECIDE` |
| POST | `/api/actions/risk-tickets/{id}/dismiss` | no | no | own | yes | no | no | no | `TicketRoles.DECIDE` |
| POST | `/api/actions/risk-tickets/{id}/edit` | no | no | own | yes | no | no | no | `TicketRoles.DECIDE` |
| POST | `/api/actions/risk-tickets/{id}/override` | no | no | own | yes | no | no | no | `TicketRoles.DECIDE` |
| POST | `/api/actions/risk-tickets/{id}/override/withdraw` | no | no | own | yes | no | no | no | `TicketRoles.DECIDE` |
| POST | `/api/actions/risk-tickets/{id}/pull-requests` | no | no | own | yes | no | no | no | `TicketRoles.DECIDE` |
| POST | `/api/actions/risk-tickets/{id}/pull-requests/{proposalId}/approve` | no | no | own | yes | no | no | no | `TicketRoles.DECIDE` |
| GET | `/api/actions/upgrade-proposals` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/actions/upgrade-proposals/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/actions/upgrade-proposals/{id}/approve` | no | no | own | yes | no | no | no | `UpgradeRoles.DECIDE` |
| POST | `/api/actions/upgrade-proposals/{id}/dismiss` | no | no | own | yes | no | no | no | `UpgradeRoles.DECIDE` |
| GET | `/api/actions/upgrade-recommendations` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/advisories` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/advisories/{sourceId}/{externalId}` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/ai/ping` | no | yes | yes | yes | yes | yes | yes | authenticated |
| GET | `/api/audit/decisions` | no | no | no | yes | yes | no | no | `DecisionTrailController.READ` |
| GET | `/api/audit/decisions/removed-services` | no | no | no | yes | yes | no | no | `DecisionTrailController.READ` |
| GET | `/api/audit/decisions/{id}` | no | no | no | yes | yes | no | no | `DecisionTrailController.READ` |
| GET | `/api/audit/evidence` | no | no | no | yes | yes | no | no | `DecisionTrailController.READ` |
| GET | `/api/change-signals` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/change-signals/facets` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/change-signals/{id}` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/findings` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/findings/my-services` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/findings/posture` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/integrations/github` | no | no | yes | yes | yes | yes | no | org admin or a domain role |
| POST | `/api/integrations/github/authorizations` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| POST | `/api/integrations/github/connections` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| DELETE | `/api/integrations/github/connections/{installationId}` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| GET | `/api/integrations/jira` | no | no | yes | yes | yes | yes | no | org admin or a domain role |
| POST | `/api/integrations/jira/authorizations` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| DELETE | `/api/integrations/jira/connection` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| POST | `/api/integrations/jira/connections` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| GET | `/api/integrations/jira/options` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| PUT | `/api/integrations/jira/settings` | no | no | no | no | no | yes | no | `ORG_ADMIN` (connect and configure) |
| GET | `/api/inventory/components` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/inventory/components` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| GET | `/api/inventory/components/ecosystems` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/inventory/components/used-by` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/inventory/components/{componentId}/dependencies` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| DELETE | `/api/inventory/components/{componentId}/dependencies/{dependencyId}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| POST | `/api/inventory/components/{componentId}/dependencies/{dependencyId}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| DELETE | `/api/inventory/components/{id}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| GET | `/api/inventory/components/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| PUT | `/api/inventory/components/{id}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| GET | `/api/inventory/owners` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/inventory/owners/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/inventory/services` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/inventory/services` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| DELETE | `/api/inventory/services/{id}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| GET | `/api/inventory/services/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| PUT | `/api/inventory/services/{id}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| GET | `/api/inventory/services/{serviceId}/components` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| DELETE | `/api/inventory/services/{serviceId}/components/{componentId}` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| POST | `/api/inventory/services/{serviceId}/components/{componentId}` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| GET | `/api/inventory/services/{serviceId}/owners` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/inventory/services/{serviceId}/owners` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| DELETE | `/api/inventory/services/{serviceId}/owners/{ownerId}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| DELETE | `/api/inventory/services/{serviceId}/repository` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| PUT | `/api/inventory/services/{serviceId}/repository` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| POST | `/api/inventory/services/{serviceId}/sbom` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| POST | `/api/inventory/services/{serviceId}/sbom/from-repository` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| GET | `/api/inventory/services/{serviceId}/vendors` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| DELETE | `/api/inventory/services/{serviceId}/vendors/{vendorId}` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| POST | `/api/inventory/services/{serviceId}/vendors/{vendorId}` | no | no | own | yes | yes | no | no | `InventoryRoles.COMPOSE` |
| GET | `/api/inventory/vendors` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/inventory/vendors` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| DELETE | `/api/inventory/vendors/{id}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| GET | `/api/inventory/vendors/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| PUT | `/api/inventory/vendors/{id}` | no | no | no | yes | yes | no | no | `InventoryRoles.WRITE` |
| GET | `/api/inventory/vendors/{vendorId}/used-by` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/matching/change-signals/{id}` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/news` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/news/{sourceId}/{externalId}` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/notifications` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/notifications/read-all` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/notifications/unread-count` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| POST | `/api/notifications/{id}/read` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/ping` | no | yes | yes | yes | yes | yes | yes | authenticated |
| GET | `/api/scoring/change-signals/{id}` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/scoring/rollups` | no | no | yes | yes | yes | no | no | `InventoryRoles.READ` |
| GET | `/api/vendor-incidents` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |
| GET | `/api/vendor-incidents/{sourceId}/{externalId}` | no | no | yes | yes | yes | no | no | `READ` (the three domain roles) |

## Review, 2026-10-23 (CS203-147)

The tables above were checked endpoint by endpoint against the code and then by the three tests (aegis-core 652
checks, identity-service 313, gateway 127, all passing). Each test was also run once against a deliberately widened
rule — service owners given inventory writes, the internal API opened to everyone, the `/api/admin/**` rule
removed — and failed every time (13, 14 and 10 failures).

**Claude Code `/security-review`** of the branch (the dashboard, CS203-130, and CS203-145–147): no high- or
medium-severity finding. It checked the dashboard's tables and store for organization isolation (`org_id`, forced
row-level security, `aegis.current_org` bound in each transaction), its native SQL (bound parameters only), the
posture endpoint and the new triage filters (`InventoryRoles.READ`; ids from another organization match nothing),
the snapshot job (each organization under its own `CurrentOrg.runAs`), the security-scanning workflow (no
`pull_request_target`, no event data in `run:` steps, a write token only for this repository's own branches) and the
frontend (React text only, app-relative links).

**What the access review found** — no role can do more than intended; these are the edges, each with a decision:

| # | Finding | Decision | Owner |
|---|---|---|---|
| 1 | The gateway exposed `/actuator/metrics` to any token, even a role-less `USER`. | Resolved in CS203-151/152: gateway and core expose only `health,info,prometheus`; `/actuator/metrics` is no longer an endpoint. | Gateway |
| 2 | `/api/ping` and `/api/ai/ping` have no `@PreAuthorize`, so any token reaches them, including a role-less `USER` and a platform admin; `/api/ai/ping` calls the ai-service. | Accepted: health checks that return no data. Revisit if they ever do. | Core |
| 3 | The gateway's `/api/admin/**` rule protects nothing today: aegis-core has no `/api/admin` endpoint. | Keep it: it's the guard any future admin endpoint gets for free. | Gateway |
| 4 | identity-service's `GET /audit` (the security log, `PLATFORM_ADMIN`) isn't routed by the gateway, so no one outside the network can use it. | Accepted: less access, not more. Route `/audit` when the admin console shows the log. | Identity |
| 5 | The gateway lets `/auth/**` through without a token, including identity's token-only `/auth` endpoints (`/auth/logout-all`, `/auth/switch-org`, `/auth/mfa/**`). identity-service refuses them without one, as its test checks. | Accepted: identity-service decides, as for every route. | Identity |
| 6 | aegis-core's and identity-service's OpenAPI documents (`/v3/api-docs`) are public, but the gateway routes neither, so only the network sees them. | Accepted. Revisit if `/v3/api-docs` is ever routed. | Platform |
| 7 | The gitleaks allow-list matches fixture text anywhere, not in named files (an entry's `paths` would allow whole files). | Accepted, as recorded in `.gitleaks.toml`. | Platform |
