# Aegis threat model

STRIDE, trust boundary by trust boundary, with the risks specific to using an LLM (OWASP Top 10 for LLM
Applications, 2025) mapped onto the same threats. Every threat has an **owner** and either a **mitigation** that
points at the code that implements it, or a **recorded decision to accept** it, with why and when to revisit.

- **Scope:** the whole system as built on `develop` on 2026-10-23: browser, the Next.js BFF, the gateway,
  identity-service, aegis-core, the ai-service and its LLM provider (Groq), the integrations (GitHub, Jira), the
  ingested feeds, PostgreSQL, and the AWS deployment (`terraform/`).
- **Out of scope:** the security of GitHub, Atlassian, Groq and AWS themselves; physical and personnel security.
- **Companion:** [data-classification.md](data-classification.md) — what data Aegis holds, where, for how long, and
  what leaves it.
- **Story:** CS203-146 (E12-2). Review it whenever a trust boundary, a role or an integration changes, and at least
  once a sprint while Aegis is being built.

## Status and owners

**Mitigated** — a defence in the code addresses the threat; the mitigation names where.
**Accepted** — the remaining risk is accepted for now, with the reason and what would make us revisit it. Each
acceptance was recorded on 2026-10-23 for CS203-146 and stands once the pull request is approved; [Accepted
risks](#accepted-risks) lists them together.

| Owner | Responsible for |
|---|---|
| **Frontend** | `frontend/`: the pages and the BFF (`src/app/api`, `src/lib/server`, `src/proxy.ts`) |
| **Identity** | `backend/identity-service`: accounts, sessions, MFA, roles, organizations, the internal API |
| **Gateway** | `backend/gateway`: routing and edge authentication |
| **Core** | `backend/aegis-core`: inventory, matching, scoring, responses, notifications, the decision trail |
| **AI** | `backend/ai-service`, and aegis-core's AI callers (`ai`, `actions.assess`) |
| **Platform** | `terraform/`, the AWS accounts, `.github/workflows` |
| **Security** | this threat model, the data classification and the accepted risks |

Each owner is the team member who maintains that component; put their name here when the team assigns it.

## Trust boundaries

```
 Internet ──TLS──▶ ALB ──▶ frontend (browser ⇄ BFF) ──▶ gateway ──▶ identity-service ─┐
                                                             └──▶ aegis-core ─────────┤──▶ PostgreSQL (RDS)
                                                                   │  ├──▶ ai-service ──▶ LLM provider (Groq)
                                                                   │  ├──▶ identity-service /internal/** (service token)
                                                                   │  ├──▶ GitHub (App), Jira (OAuth)
                                                                   │  └──▶ feeds: NVD, OSV, vendor status, news, fix commits
                                                                   └── scheduled jobs (one instance, SchedulerLease)
```

| # | Boundary | What crosses it |
|---|---|---|
| B1 | Browser ⇄ frontend BFF | httpOnly session cookies, page requests, form posts |
| B2 | BFF → gateway | the access token, the client's address (`X-Forwarded-For`) |
| B3 | Gateway → identity-service, aegis-core | signed JWTs (`sub`, `roles`, `org`) |
| B4 | identity-service (accounts and sessions) | passwords, MFA codes, refresh tokens |
| B5 | aegis-core's organizations (tenancy) | every organization's estate, responses and decisions, in one database |
| B6 | aegis-core → ai-service → LLM provider | advisory text, Aegis's facts, code excerpts; drafts and assessments back |
| B7 | aegis-core → identity-service internal API | organization ids; owner-notification email requests |
| B8 | aegis-core ⇄ GitHub and Jira | repository reads and clones, draft pull requests; issues filed |
| B9 | Feeds and fix commits → aegis-core | untrusted advisories, incidents, news and diffs |
| B10 | Data stores and AWS | the database, secrets, logs, the network edge |
| B11 | Source, dependencies and CI | third-party code, workflows, secrets in the repository |

## Threats

### B1 — Browser ⇄ frontend BFF

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B1.1 | S, I | Script in the page (XSS, a malicious package) steals the session | Tokens live only in `httpOnly` cookies the BFF reads server-side; client code never sees them (`lib/server/session.ts`, `lib/server/backend.ts`). React escapes all rendered text, and nothing renders raw HTML (no `dangerouslySetInnerHTML`), so feed and AI text can't become markup. | Frontend | Mitigated |
| B1.2 | T | Cross-site request forgery against the BFF's state-changing routes | Session cookies are `SameSite=Lax`, so a cross-site POST or `fetch` carries no session; `Secure` behind HTTPS (`AUTH_COOKIE_SECURE=true` in `terraform/environments/*/locals.tf`). | Frontend | Mitigated |
| B1.3 | S | A stolen or left-behind session stays usable | Access tokens last 15 minutes; refresh tokens 7 days, single-use and rotated on every refresh; logout revokes them, and a password change signs the account out everywhere (`AuthService`). | Identity | Mitigated |
| B1.4 | E | Bypassing the route guard with a forged cookie | `proxy.ts` only checks a session cookie exists, by design; every API call is authorized again by the gateway and the service (§B3), so a forged cookie earns an empty page. | Frontend | Mitigated |
| B1.5 | T, I | Clickjacking, script injection or downgrade, for lack of security headers (no `Content-Security-Policy`, `frame-ancestors`, `Strict-Transport-Security`, `X-Content-Type-Options`) | **Accepted.** `next.config.ts` sets none. React's escaping, httpOnly + SameSite cookies and TLS-only ALB listeners cover the main paths, and no page performs a one-click destructive action. Revisit before prod goes live: add the headers in `next.config.ts` (`headers()`), with a CSP that allows the app's own origin only. | Frontend | Accepted |

### B2 — BFF → gateway

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B2.1 | E | A crafted path (`..%2F`) makes a BFF proxy forward to another gateway route | `gatewayPath` resolves each path as `fetch` would and refuses any that leaves its prefix (`lib/server/api.ts`); `e2e/bff-proxy.spec.ts` tests every proxy. | Frontend | Mitigated |
| B2.2 | S | A client fakes `X-Forwarded-For` to dodge the sign-in rate limit or poison the audit log | The BFF forwards only the address its trusted proxies recorded (`TRUSTED_PROXY_HOPS`, `lib/server/backend.ts`); the gateway passes the header on only from `GATEWAY_TRUSTED_PROXIES` and replaces anyone else's with their own address (`ForwardedForFilter`). | Gateway | Mitigated |
| B2.3 | I | Tokens travel in plain HTTP between containers | **Accepted.** TLS ends at the ALB; behind it, services talk inside private subnets, and each accepts only its listed callers (`modules/security-groups`). Revisit if any service moves outside the VPC or a compliance regime requires encryption in transit inside it. | Platform | Accepted |

### B3 — Gateway → services

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B3.1 | S | A forged, altered or expired JWT | RS256 tokens validated against identity-service's JWKS at the gateway (`gateway/SecurityConfig.java`) and again by each service (resource server). | Gateway | Mitigated |
| B3.2 | E | Reaching an internal or admin endpoint from the internet | The gateway routes only `/auth`, `/oauth2`, `/users`, `/orgs` and `/api` (`RouteConfig.java`); `/api/admin/**` needs `PLATFORM_ADMIN`; identity's `/internal/**` and the ai-service have no route, and the ai-service has no host port. | Gateway | Mitigated |
| B3.3 | E | Acting in an organization one doesn't belong to by editing the `org` claim | The claim is signed and set only from a server-verified membership (`AuthService` login and `switch-org`); aegis-core reads the organization from it (`CurrentOrg`). | Identity | Mitigated |
| B3.4 | D | Flooding the API outside `/auth` | **Accepted.** Only `/auth/**` is rate-limited. The heavy endpoints carry their own limits (evidence export, B5.8; AI budgets, B6.7), and ECS capacity absorbs the rest at today's scale. Revisit at public launch: AWS WAF rate rules on the ALB. | Platform | Accepted |

### B4 — identity-service: accounts and sessions

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B4.1 | S | Credential stuffing or password guessing | Per-IP token bucket on `/auth/**` (`RateLimitFilter`, 20 per minute); account lockout after 5 failures for 15 minutes (`LoginAttemptService`); a second factor — an emailed code by default, TOTP or a passkey (WebAuthn) when enrolled (`auth/mfa`). | Identity | Mitigated |
| B4.2 | I | A database leak exposes credentials | Passwords hashed with bcrypt (`PasswordEncoderFactories`, `config/SecurityConfig.java`); refresh tokens, account-flow tokens, login codes and recovery codes stored as SHA-256 hashes (`RefreshToken`, `AuthToken`, `LoginChallenge`, `MfaRecoveryCode`); TOTP secrets encrypted with AES-256-GCM (`SecretCipher`, key `MFA_TOTP_SECRET_KEY` in SSM). | Identity | Mitigated |
| B4.3 | S | The token-signing key leaks, so anyone can mint tokens | The key comes from SSM (`IDENTITY_JWT_PRIVATE_KEY`), is never logged, and its key id is its thumbprint (`SigningKey`). **Accepted:** rotating it is a manual change that signs everyone out; no overlap of old and new keys. Revisit if it ever leaks, or before prod: publish two keys during a rotation. | Identity | Accepted |
| B4.4 | E | An organization admin promotes themselves or changes another admin | Org admins assign only the three domain roles and can neither grant nor change `ORG_ADMIN`; platform admins never assign organization roles (`UserService`). | Identity | Mitigated |
| B4.5 | R | Someone denies a sign-in or an account change | `audit_events` records every sign-in, MFA step and account change with the client's address and user agent; append-only by trigger (`V5__audit_events.sql`); readable by platform admins only (`AuditController`). | Identity | Mitigated |
| B4.6 | D | An attacker locks a victim out by failing their sign-in on purpose | **Accepted.** The 15-minute lockout is the price of B4.1; the per-IP limit slows it. Revisit if it's seen: lock per account and address instead. | Identity | Accepted |

### B5 — aegis-core: organizations, roles and decisions

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B5.1 | I, T | One organization reads or changes another's data | Every store scopes reads and writes by the token's organization (`CurrentOrg`), and every organization-scoped table forces row-level security through `current_org()` (V9, V31; all 22 tables). The integration tests connect as a plain role, as on RDS, so the policies are tested (`RowLevelSecurityIntegrationTest`, `DecisionTrailIsolationIntegrationTest`). | Core | Mitigated |
| B5.2 | E | A role does more than its duty: an owner acts on a service they don't own, a compliance admin decides | `@PreAuthorize` on every handler with the role rules in `InventoryRoles` (an owner composes only their own services), `TicketRoles`, `UpgradeRoles`, `MemoRoles` (analysts only), `AssessRoles`; compliance admins read only. CS203-147 tests every endpoint against the role table. | Core | Mitigated |
| B5.3 | E | The person who drafted a repository change also approves it | Never the drafter of a response with a pull request, nor of the pull request (`RiskTicketService.approve`, `UpgradeProposalService`), per RTS 2024/1774 Art. 17(1)(b). | Core | Mitigated |
| B5.4 | T | Two people decide at once, or someone decides on a stale page | An optimistic version on every response, the row locked while a decision is checked and written (`findForUpdateByIdAndOrgId`), and the version the reviewer saw checked: the loser gets a 409. | Core | Mitigated |
| B5.5 | S, E | A client assigns an arbitrary user id as a service owner | Assignment verifies the person is a member holding `SERVICE_OWNER` by asking identity-service with the caller's own token (`HttpIdentityDirectory`), so identity applies the caller's organization. | Core | Mitigated |
| B5.6 | R | A reviewer denies a decision, or a decision isn't recorded | Every step of a response is saved with its record in one transaction (`DecisionTrail.recorded`); the stores refuse a write made otherwise (`UnrecordedStep`); `decision_records` is append-only by trigger (V36) and keeps who, when, why and the response as it stood. | Core | Mitigated |
| B5.7 | T | Someone with the database role aegis-core connects as rewrites the decision trail | **Accepted.** The append-only triggers bind ordinary writes, not the table's owner, which is the role aegis-core uses. Revisit before prod: the owner/app role split in `todo/aws-deployment.md`. | Platform | Accepted |
| B5.8 | D | Large or many evidence exports exhaust memory or the database | At most 5,000 records per CSV and 2,000 per PDF, two exports at once, otherwise 429 (`EvidenceExport`). | Core | Mitigated |
| B5.9 | I | Row-level security is bypassed in docker compose, which connects as a superuser | **Accepted.** Local development only; RDS has no superuser and no role there can bypass row-level security, and the tests use a plain role. | Platform | Accepted |

### B6 — aegis-core → ai-service → LLM provider

What is sent to the provider (Groq, when `LLM_PROVIDER=groq`; the default is an offline stub that sends nothing):
- **Drafts** (risk tickets, upgrade rationales, DORA memos): the change's published description and Aegis's own
  facts — affected services, packages, versions, vendors, impact scores — and how many owners each service has,
  never who (`RiskTicketService.context`, `RegulatorMemoService`).
- **Reachability assessments** (E11-5): the advisory text (≤ 2,500 characters), what its fix commit changed
  (≤ 1,200), the package, each search term's counts, and up to 6 code excerpts of ±3 lines (≤ 600 characters each)
  with their file paths — never whole files, the repository's address, the service's name or its owners
  (`ReachabilityAssessments`, `RepositoryCode`).
- Everything is redacted twice: by `SensitiveText` before it leaves aegis-core (`AiDtos`), and by the ai-service's
  `_guardrails.redact` before it reaches the model.

| ID | STRIDE / LLM | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B6.1 | I / LLM02 | Secrets or personal data reach the provider | The minimal context above; pattern redaction on both sides of the boundary (`SensitiveText`, `_guardrails.py`: private keys, credentials in URLs, provider keys, bearer/basic tokens, JWTs, `password=`-style assignments, emails, phone numbers, ID numbers); search terms that name secrets are refused on both sides. | AI | Mitigated |
| B6.2 | T / LLM01 | Indirect prompt injection: an advisory, a news item, a fix diff or a code comment tells the model to do something else | Every input is screened for instructions after Unicode normalisation (`check_sources` in `draft.py`, `assess.py`, `look_for.py`), and refused before the model sees it; prompts keep instructions and data apart; the output must fit a strict JSON schema (`llm/groq.py`); the model has no tools; nothing it says acts without a person's approval. | AI | Mitigated |
| B6.3 | E / LLM06 | Excessive agency: the model triggers an action | No tools or function calls are offered, and a response that asks for one is rejected (`llm/groq.py`); output is text in schema fields only; a claim to have filed, opened, merged or sent something is rejected (`_EXECUTION_CLAIM`); filing, opening and finalising happen only through the approval endpoints and their RBAC (B5.2, B5.3). | AI | Mitigated |
| B6.4 | T / LLM05 | Model or feed text becomes markup, mentions or links where it lands | Pull-request text is made inert (no `@`-mentions, cross-references, remote images or HTML: `UpgradePullRequestText.inert`); Jira descriptions are built from text nodes only (`JiraIssueTracker`); memo templates render in one pass, so drafted text is never read as a placeholder (`MemoTemplate`); notification emails are written by identity-service with links defanged (B7.2); the UI escapes everything (B1.1). | Core | Mitigated |
| B6.5 | R / LLM09 | The model invents sources or overstates a conclusion, and a reviewer relies on it | Citations are passage ids that the server resolves to what it sent, and an answer citing anything else is refused (`validate_citations`, `_grounding.py`); "called" must cite an excerpt, "not called" an excerpt or the search; drafts are labelled AI-drafted and unverified; the assessment is a suggestion that never changes a score or a decision (`ImpactScorer` doesn't read it). | AI | Mitigated |
| B6.6 | I / LLM07 | The model reveals its system prompt or something secret in it | Prompts contain no secrets or credentials; requests to reveal the system prompt are screened out (B6.2); output is redacted again before it leaves the ai-service (`protect_output`). | AI | Mitigated |
| B6.7 | D / LLM10 | Unbounded use: a loop or a busy organization runs up cost or starves the others | Per-minute request and token limits and a daily token budget (`CostLimiter`, `llm/limits.py`; 7,000 tokens a minute, 150,000 a day by default); at most 1,500 output tokens and 16,000 input characters per call; a 20-second timeout; aegis-core waits out one 429 at most (`HttpAiClient`); assessments run only when asked or when a ticket is drafted, never on a page load. **Accepted:** the budgets are per ai-service process, not per organization, so one organization can use up a day's budget for all. Revisit when a second organization uses AI in earnest: a per-organization quota in aegis-core. | AI | Accepted |
| B6.8 | S | Something other than aegis-core calls the ai-service | `X-Service-Token`, compared in constant time (`core/security.py`); the ai-service is on the internal network only, with no route or host port; in AWS only aegis-core may reach it (`modules/security-groups`). | AI | Mitigated |
| B6.9 | I / LLM03 | The provider retains or reuses what Aegis sends, under its own terms | **Accepted.** What is sent is minimal and redacted (B6.1); the provider is off by default (`LLM_PROVIDER=stub`) and chosen per deployment; there is no data-processing agreement with Groq. The public privacy notice describes what is sent but doesn't name the provider. Revisit before prod: name the provider and link its data policy on `/privacy`, and check its retention terms. | Security | Accepted |
| B6.10 | T / LLM04 | Poisoned feed data steers the AI into a wrong recommendation | The AI never ingests feeds, matches packages, scores or decides: matching, upgrade targets and scores are deterministic code (`matching`, `scoring`); the AI writes only narrative around them, and a person approves every draft. | Core | Mitigated |
| B6.11 | I | A fix-commit link in an advisory makes aegis-core fetch an internal or arbitrary address | Only `…/commit/{sha}.diff` on github.com or gitlab.com, rebuilt from validated parts (`CommitUrl`); no redirects, a 200 only, at most 1 MB, no credentials (`HttpDiffSource`). | AI | Mitigated |

There is no retrieval-augmented generation yet, so LLM08 (vector and embedding weaknesses) doesn't apply.

### B7 — aegis-core → identity-service internal API

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B7.1 | S | Something other than aegis-core calls `/internal/**` | Its own filter chain: only `X-Service-Token`, compared in constant time, and an empty token refuses every call (`InternalSecurityConfig`); no gateway route; in AWS only the gateway and aegis-core reach identity-service (`modules/security-groups`). | Identity | Mitigated |
| B7.2 | T | Feed text in an owner-notification email becomes a phishing link | identity-service writes the email: item text is one line with URLs removed and hosts defanged (`github[.]com/…`), and the only links are the app's own address plus a validated path (`InternalController.APP_PATH`, `SmtpEmailSender`). | Identity | Mitigated |
| B7.3 | I | An email goes to someone outside the organization | Sent only to an enabled user with a verified address who is an active member of the named organization (`InternalController`). | Identity | Mitigated |
| B7.4 | T | A retry after a timeout sends the same email twice | Each email carries a delivery id, sent once (`owner_email_deliveries`, V16); aegis-core asks again under the same id and backs off for about 13 hours (`OwnerNotifier`). | Core | Mitigated |
| B7.5 | E | A leaked service token lets an attacker list organizations and send Aegis-worded emails to members | **Accepted.** The token is in SSM; the API can't send free text or outside links (B7.2) and only to active members (B7.3). Revisit on any sign of misuse: rotate `identity-internal-token` in SSM and redeploy both services. | Platform | Accepted |

### B8 — GitHub and Jira

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B8.1 | S, E | An organization binds a GitHub App installation it doesn't control, and reads someone else's repositories | Connecting needs a GitHub sign-in proving control — the admin's own account, or a GitHub organization they administer (`GitHubUserAuthorization`); the sign-in's token is revoked after the check; installation tokens are minted only for installations the caller's organization connected (`GitHubConnections`). | Core | Mitigated |
| B8.2 | I | An installation token reaches more than the job needs | Minted for one repository with only the access the job needs — `contents: read` to clone, `contents` + `pull_requests: write` to open a pull request — and the connection checked again on every use, even from the in-memory cache; GitHub expires it after an hour (`GitHubInstallationTokens`). | Core | Mitigated |
| B8.3 | T | Aegis changes a repository nobody approved, or merges | Pull requests open only on approval, as drafts on an `aegis/` branch, never merged (`GitHubAppPrProvider.openDraftPullRequest`); four eyes (B5.3); manifests are read again at approval and each write is pinned to the revision just read, so a file changed meanwhile is refused (`UpgradeProposalService`). | Core | Mitigated |
| B8.4 | S | A forged or replayed Jira consent binds an attacker's site to an organization | The OAuth `state` is single-use, lasts 10 minutes and is bound to the admin and their organization (`JiraConnections`). | Core | Mitigated |
| B8.5 | I | A database leak exposes Jira tokens | Sealed with AES-256-GCM under `AEGIS_SECRETS_KEY` (SSM), with the organization bound in as associated data so a token can't be moved to another organization (`SecretBox`); refresh tokens are renewed under a row lock, as Atlassian rotates them. | Core | Mitigated |
| B8.6 | T, D | A malicious repository attacks the SBOM import: git option or protocol injection, symlinks out of the checkout, huge or endless content | Hosts allow-listed (`AEGIS_SBOM_ALLOWED_HOSTS`); the clone URL rebuilt from validated parts; `file`/`ext` protocols off; credentials through `GIT_ASKPASS`, never on disk or in arguments; partial, sparse clone; size caps and hard timeouts; a fresh temp directory always deleted (`RepoSbomGenerator`). syft only reads files and never runs code; the import scanner skips symlinks, vendored and build folders, files over 1 MB and stops at 20,000 files / 128 MB (`ImportScanner`). | Core | Mitigated |
| B8.7 | I | A token pasted for an import is stored or leaks in an error | Used for that import only, never stored, and scrubbed from any error returned (`RepoSbomGenerator`). | Core | Mitigated |

### B9 — Feeds and fix commits

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B9.1 | I, D | A feed or a redirect steers aegis-core at an internal address (SSRF), or streams an endless body | Every URL and every redirect is checked (`OutboundHttp.requireFetchable`), redirects are followed one by one so each hop is checked, bodies are read with a cap (`readCapped`), and timeouts are always set (`FeedHttpClient`). Feeds use their own HTTP client, so the ai-service's token is never sent to a publisher. | Core | Mitigated |
| B9.2 | T | A wrong or malicious advisory leads to a harmful upgrade | Deterministic parsing and version matching; one row per CVE across feeds (V21); the safe version is worked out across every advisory for the package (`UpgradeTargets`); the pull request lists every advisory checked; a person approves it, and it opens as a draft. | Core | Mitigated |
| B9.3 | D | A slow or failing publisher stalls ingestion | Timeouts on every fetch, per-feed metrics, a six-thread scheduler pool, and one instance running the jobs (`SchedulerLease`). | Core | Mitigated |

### B10 — Data stores and AWS

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B10.1 | I | The database is reached from outside or read from its disks | RDS is not publicly accessible, accepts only identity-service and aegis-core, and is encrypted at rest (`modules/rds-postgres`, `modules/security-groups`). | Platform | Mitigated |
| B10.2 | I | Secrets leak through the repository or Terraform state | Secrets are SSM SecureString parameters created by a person; Terraform builds only their ARNs, so values never enter state (`modules/ssm-secrets`); `.env` is git-ignored; gitleaks checks every pull request (B11.2). | Platform | Mitigated |
| B10.3 | I, T | Traffic is read or changed on the way in | The ALB listens with TLS 1.3/1.2 only (`ELBSecurityPolicy-TLS13-1-2-2021-06`) and redirects HTTP to HTTPS (`modules/alb`). | Platform | Mitigated |
| B10.4 | I | Logs or traces collect personal data or secrets | Tracing is off in AWS (`TRACING_SAMPLE_RATE=0`); container logs are kept 14 days; the redaction code never logs its input or what it removed (`SensitiveText`, `_guardrails.py`); the development email sender that logs account tokens runs only without an SMTP host, which AWS always sets (`SPRING_MAIL_HOST`). | Platform | Mitigated |
| B10.5 | E | A compromised container moves to other services | Each service accepts only its listed callers on its own port (`modules/security-groups` `callers`). | Platform | Mitigated |
| B10.6 | D | Data is lost | **Accepted.** Automated backups for 7 days in one region, deletion protection in prod. Revisit before prod holds customer data: a restore drill and longer or cross-region backups. | Platform | Accepted |

### B11 — Source, dependencies and CI

| ID | STRIDE | Threat | Mitigation or decision | Owner | Status |
|---|---|---|---|---|---|
| B11.1 | T | A vulnerable dependency is merged | Dependency review fails a pull request adding any known-vulnerable dependency, with each Java service's resolved Maven graph submitted first (`.github/workflows/security-scanning.yml`, CS203-145). | Platform | Mitigated |
| B11.2 | I | A secret is committed | gitleaks scans the commits every pull request and push adds; the only exceptions are listed fixtures (`.gitleaks.toml`). | Platform | Mitigated |
| B11.3 | T | Vulnerable code patterns are merged | CodeQL analyses the Java, TypeScript/JavaScript and Python code on every pull request and weekly (`.github/workflows/security-scanning.yml`); its check fails a pull request at the severity threshold set in the repository's code-scanning settings. | Platform | Mitigated |
| B11.4 | T | The ai-service's Python dependencies aren't reviewed | **Accepted.** GitHub's dependency graph doesn't read a PEP 621 `pyproject.toml`. Revisit next: commit a lock file (or a `requirements.txt` export) the dependency graph reads. | AI | Accepted |
| B11.5 | T | A compromised third-party action runs in CI | **Accepted.** Actions are pinned by major version tag, not commit SHA; the gitleaks binary is pinned and checksummed. Revisit when the repository holds deployment credentials in CI: pin every action by SHA. | Platform | Accepted |

## OWASP Top 10 for LLM Applications (2025)

| Risk | Where it's handled |
|---|---|
| LLM01 Prompt injection | B6.2 (indirect, through feeds, diffs and code); there is no free-form prompt for users to inject into directly |
| LLM02 Sensitive information disclosure | B6.1, B6.9 |
| LLM03 Supply chain | B6.9 (the provider), B11 |
| LLM04 Data and model poisoning | B6.10 |
| LLM05 Improper output handling | B6.4 |
| LLM06 Excessive agency | B6.3 |
| LLM07 System prompt leakage | B6.6 |
| LLM08 Vector and embedding weaknesses | Not applicable: no retrieval-augmented generation yet |
| LLM09 Misinformation | B6.5 |
| LLM10 Unbounded consumption | B6.7 |

## Accepted risks

Recorded 2026-10-23 for CS203-146; each stands once this document's pull request is approved, and is reviewed when
its revisit condition is met.

| ID | Accepted risk | Owner | Revisit when |
|---|---|---|---|
| B1.5 | No security headers (CSP, frame-ancestors, HSTS) | Frontend | Before prod goes live |
| B2.3 | Plain HTTP between containers inside the VPC | Platform | A service leaves the VPC, or a regime requires it |
| B3.4 | No API rate limit outside `/auth` | Platform | Public launch (WAF rate rules) |
| B4.3 | No overlapping signing keys, so rotation signs everyone out | Identity | A leak, or before prod |
| B4.6 | Lockout can be triggered against a victim | Identity | It is seen in the audit log |
| B5.7 | The decision trail's triggers don't bind the owning database role | Platform | Before prod (owner/app role split) |
| B5.9 | Row-level security off in docker compose (superuser) | Platform | Never, while compose is local-only |
| B6.7 | AI budgets per process, not per organization | AI | A second organization uses AI in earnest |
| B6.9 | No data-processing agreement with the LLM provider; not named on `/privacy` | Security | Before prod |
| B7.5 | A leaked internal service token can email members Aegis-worded items | Platform | Any sign of misuse (rotate) |
| B10.6 | 7-day, single-region backups; no restore drill | Platform | Before prod holds customer data |
| B11.4 | Python dependencies not reviewed | AI | Next sprint |
| B11.5 | CI actions pinned by tag, not SHA | Platform | CI holds deployment credentials |

The data classification lists the retention gaps (no retention limit on several stores) as accepted decisions of
the same kind.
