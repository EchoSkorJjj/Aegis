# GitHub App provider spike (E8 Story 3)

Story 3 implements a disabled-by-default `PrProvider` in `actions.pr.github`. It can create a branch,
commit proposed file contents and open a **draft** PR. There is no execution endpoint, scheduled job,
approval workflow, merge operation, or real GitHub App registration in this change. Tests use only a
local fake GitHub server and runtime-generated temporary RSA keys.

## Decision and scope

Use a GitHub App: installations can be restricted to selected repositories, and installation tokens
have short lifetimes and independently narrowed permissions. A fine-grained PAT is a possible demo
fallback behind `PrProvider`; no PAT adapter is implemented. The App is independent of an employee's
personal credentials. See GitHub's [App authentication model](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/about-authentication-with-a-github-app).

Only `PrProvider` is published as an adapter bean. Authentication, configuration, HTTP mapping and
token caching stay inside `actions.pr.github`; the existing architecture test guards this boundary.
Inventory repository links do not call this adapter. Linking a repository does not authorize execution.

## Configuration

All keys are under `aegis.actions.github` in `application.yml`.

| Property | Environment variable | Default |
|---|---|---|
| `enabled` | `AEGIS_GITHUB_ENABLED` | `false` |
| `api-base-url` | `AEGIS_GITHUB_API_URL` | `https://api.github.com` |
| `client-id` | `AEGIS_GITHUB_APP_CLIENT_ID` | Required when enabled |
| `private-key-path` | `AEGIS_GITHUB_APP_PRIVATE_KEY_PATH` | Required when enabled; external PKCS#8 PEM |
| `hosts` | `AEGIS_GITHUB_HOSTS` | `github.com` |
| `allowed-accounts` | `AEGIS_GITHUB_ALLOWED_ACCOUNTS` | Empty: refuse every operation |

Comma-separated host and account lists are case-insensitive. An enabled provider requires a client ID,
a valid API URL and a readable, unencrypted RSA PKCS#8 key of at least 2048 bits at startup. When off,
the configuration and provider beans are absent and no key is needed. `hosts` selects repository
hosts; it does not choose an API origin from user input. This deployment uses one configured API
origin, including an optional Enterprise `/api/v3` prefix. HTTPS is required except for loopback HTTP
used by tests. Redirects are refused, including redirects for renamed repositories.

`docker-compose.yml` intentionally leaves the spike off and mounts no key. For a later sandbox
exercise, explicitly supply these environment variables to the **aegis-core container** and mount
the external key read-only using a local Compose override. The path must be the container path.
Putting values in the root `.env` alone does not pass new variables through Compose.

## Authentication and draft flow

1. Sign an RS256 App JWT with the client ID as `iss`, `iat = now - 60s`, and `exp = now + 9m`.
   See GitHub's [JWT requirements](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app).
2. Look up `/repos/{owner}/{repo}/installation` using that JWT. A 404 reports that the Aegis App
   is not installed on the repository. Check `account.login` against the configured account allow-list.
3. Request `/app/installations/{id}/access_tokens` with exactly one repository name and
   `contents: write`, `pull_requests: write`. Cache only in memory by installation ID and repository
   name; use `expires_at` and refresh below five minutes remaining. Recheck the installation account
   even for a cache hit. Expired entries are discarded. GitHub tokens normally last one hour and are
   treated as opaque strings, without a fixed-length assumption. See
   [installation token scoping](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app).
4. Resolve the supplied base, or fetch `default_branch`; reject a default equal to the proposed head
   before any repository write. Fetch the base SHA, then create `refs/heads/aegis/...`.
5. For each file, fetch its existing SHA on the head branch (404 means new), then PUT the base64 UTF-8
   contents with the head and commit message. Existing targets must be regular files. Proposal
   validation rejects `.github/workflows/**`, unsafe paths and oversized changes.
6. Create a PR with `draft: true`, returning its URL, number and head through the neutral port.

Every call sends `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2026-03-10`, and a
per-request Authorization header. App JWTs are used only for installation/token endpoints. The
private HTTP client has two-second connect and 30-second read timeouts and uses the application's
observation registry. It neither follows redirects nor retries failed operations. Error bodies and
underlying HTTP/JSON exceptions are discarded before HTTP observations can record them; credentials
are never logged or persisted by the adapter.
401/403 map to `UpstreamUnavailable`, missing resources to `NotFound`, and 409/422 to `Conflict`.

The [2026-03-10 breaking changes](https://docs.github.com/en/rest/about-the-rest-api/breaking-changes)
were reviewed: this adapter uses `default_branch`, not `master_branch`, and does not consume removed
repository settings. The contents API's changed directory-listing submodule type does not affect its
individual-file flow; non-file responses are refused. Response records ignore unknown properties.

The Contents API makes **one commit per file** here. Atomic multi-file changes would need the Git Data
API: blobs, tree, commit, then update the ref. A failure after branch creation may leave a branch or
partial commits. The adapter does not delete them, force-push, retry writes, or reuse an existing
branch. Inspect a failed attempt before retrying with a new unique `aegis/*` head. A timeout can mean a
remote write succeeded without its response reaching Aegis; future execution needs idempotency and
reconciliation. Rate-limit responses fail safely; scheduling/backoff belongs to that future executor.

## Tenant security and key operations

**The allow-list is a spike safeguard, not production tenant isolation.** With one App installed on
multiple accounts, a tenant could otherwise link another tenant's repository and invoke the victim's
installation. Empty lists refuse all operations; unmatched accounts are rejected before token minting.
An allow-listed account can still be shared by multiple Aegis tenants.

Before production, an org admin must explicitly verify and bind an installation to their Aegis org.
Store that binding in a new table with `org_id`, scope it through `CurrentOrg`, and require every action
to use a bound installation. Add cross-tenant denial tests, installation removal/suspension handling,
and approval/audit enforcement before exposing any execution route or job. No binding migration is
included in this spike.

Keep private keys outside the repository tree, build context and image, with access restricted to the
service account. The loader expects PKCS#8; convert GitHub's downloaded PKCS#1 key once, outside the
checkout:

```sh
openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pkcs8.pem
```

Epic 3 Story 4 should move key storage and delivery to a secret manager, define access auditing and
rotation/revocation procedures, and verify operational redaction (including HTTP wire/debug logging).
Keys are loaded once at startup: deploy/restart with a new key before revoking the old key. Overlap
keys during rotation. A process restart clears its token cache; suspending/uninstalling the App or
revoking installation tokens is needed to stop already-issued access during an incident. Do not log
configuration dumps, request headers or token responses.

## Later manual App setup (not performed)

1. Go to Settings → Developer settings → GitHub Apps → New GitHub App. Choose the intended App owner
   and installation visibility. Disable webhooks; this spike does not consume them.
2. Grant repository **Contents: Read & write**, **Pull requests: Read & write**, and mandatory
   **Metadata: Read**. Leave every other permission at No access, especially Workflows,
   Administration and Actions.
3. Install on **Only select repositories**, initially one disposable sandbox repository. Record its
   owner/account login for the spike allow-list. See GitHub's
   [least-privilege guidance](https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/best-practices-for-creating-a-github-app).
4. Generate and securely store the key outside the checkout, convert it as above, and mount it
   read-only. GitHub supports overlapping keys for rotation; follow its
   [private-key management guide](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/managing-private-keys-for-github-apps).
5. Protect the default branch with reviews and checks. Contents write permission is not restricted
   to `aegis/*` by GitHub; that restriction is enforced by this provider and proposal validation.
6. Installation-token commits use the App's bot identity. The desired slug is `aegis`; names are
   globally unique, so record `aegis-cs203[bot]` as a fallback and verify the actual author in the sandbox.

## Verification and future sandbox smoke test

Verified on 2026-09-25 using Java 21 / Maven in Docker and a disposable Testcontainers PostgreSQL:

| Check | Result |
|---|---|
| `mvn -B clean verify` | 460 tests passed; zero failures, errors or skips; JAR packaging and Spotless passed |
| `mvn -B verify -Dsurefire.runOrder=reversealphabetical` | 460 tests passed; packaging and Spotless passed |
| Included GitHub adapter/configuration tests | 41 passed, including caller and tracing error redaction |
| Included architecture checks | All 8 passed |

Run from `backend/aegis-core` with Java 21, Maven and Docker available:

```sh
mvn -B clean verify
mvn -B verify -Dsurefire.runOrder=reversealphabetical
# Focused, no database required:
mvn -B -Dtest=GitHubAppPrProviderTest,GitHubPrConfigTest,PackageStructureArchTest test
```

The local fake checks signed JWT claims, request headers, token scopes, cache reuse/refresh/isolation,
account refusal before minting, default and explicit branches, Enterprise API prefixes, encoded file
paths, existing/new file commits and draft creation. It exercises missing installations, conflicting
branches, upstream errors, malformed responses and discarded credentials (including tracing errors), redirect refusal, and
startup behavior with missing/invalid keys or the feature disabled. Existing integration tests boot
the whole application with no provider enabled. Test PEMs are generated under temporary directories.

Real App registration, installation permissions, bot attribution, branch-protection behavior and
Enterprise compatibility remain unverified against GitHub. API-version support must be confirmed for
the particular Enterprise Server release before deployment.

When an operator is ready for a **separately authorized** real sandbox exercise:

1. Register/install the least-privilege App above, use a disposable repository containing one sample
   file, and supply only that sandbox account in `allowed-accounts`.
2. Enable the adapter in an isolated local environment with an external key. No UI action or HTTP
   endpoint invokes it yet. A developer must use a temporary local harness calling `PrProvider`
   directly, or the future human-approved executor. Do not add an unauthenticated test endpoint.
3. Submit a `PullRequestDraft` with a unique `aegis/smoke-<timestamp>` head, null base, and one harmless
   sample-file change. Verify the returned URL, draft state, default base, exact diff and bot author.
   Check that the default branch and workflow files remain untouched.
4. Repeat with a disallowed account to verify refusal, then disable the adapter, close the draft,
   remove the sandbox branch and uninstall the sandbox installation when finished. Record sanitized
   evidence without JWTs, installation tokens or key material.

Open questions for production: org-installation verification UX; App ownership and final slug;
approval permissions/audit schema; idempotency and partial-failure cleanup; atomic multi-file commits;
cache invalidation on revocation; GitHub Enterprise versions; secret-manager and rotation ownership.

## Suggested PR description

Adds a disabled GitHub App implementation of the draft-PR port. It resolves repository installations,
checks an account allow-list, caches repository-scoped installation tokens, and creates an `aegis/*`
branch, file commits and draft PR. All adapter HTTP tests use a local fake with temporary RSA keys.
There is no execution route or real App registration. Production still requires verified org-scoped
installation bindings, human approval, audit, and retry/reconciliation design. See this write-up for
permissions, key operations and the future sandbox smoke procedure. Validation: all 460 backend tests
passed in both default and reverse class order, including 41 GitHub checks; packaging and formatting
checks passed.
