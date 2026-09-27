# Jira: filing approved risk tickets (E8 story 5)

A risk ticket reaches Jira only when its response is approved (`POST /api/actions/risk-tickets/{id}/approve`,
see the aegis-core README): by someone other than its drafter when an upgrade pull request goes with it, or by
its drafter when the ticket only records a risk. Nothing is filed on a schedule. Each Aegis organization connects **its own** Jira site: its organization admin grants Aegis
access through Atlassian's consent screen, then chooses the project and issue type. Aegis never edits,
comments on, transitions or deletes an issue after it has filed it.

ServiceNow has no connection. Every ticket page shows the same ticket as ServiceNow incident fields to
paste, or to POST to ServiceNow's Table API (`/api/now/table/incident`).

The adapter lives in `actions.tracker.jira` and is reached only through the `IssueTracker` port; the
architecture test enforces this. It is off unless `AEGIS_JIRA_ENABLED=true`.

## 1. Create the OAuth 2.0 integration (Atlassian developer console)

Do this once per Aegis deployment, as the Atlassian account that will own the integration.

1. Open the [developer console](https://developer.atlassian.com/console/myapps/) and choose **Create** →
   **OAuth 2.0 integration**. Name it (for example `Aegis`).
2. **Grant type: resource-level.** The admin's consent then covers only the Jira site they pick on the
   consent screen, and the tokens reach only that site. Atlassian's consent screen takes one site at a time.
   Atlassian sets the grant type when the integration is created, so if yours is account-level, create a
   new integration. Aegis works with either type: with an account-level grant, the admin chooses the site
   in Aegis instead.
3. **Permissions** → **Jira API** → **Add** → **Configure** → **Edit Scopes**. Under **Classic scopes**,
   select exactly these two and nothing else:

   | Scope | Shown as | What Aegis uses it for |
   |---|---|---|
   | `read:jira-work` | View Jira issue data | Listing the projects you can create issues in (`GET /rest/api/3/project/search?action=create`) and a project's issue types (`GET /rest/api/3/project/{key}`) |
   | `write:jira-work` | Create and manage issues | Creating the approved ticket (`POST /rest/api/3/issue`) |

   Add no granular scopes and no other APIs (Confluence, user identity, `read:jira-user`, `manage:jira-*`).
   Atlassian recommends classic scopes, and Aegis asks for these two by name, so the integration must
   have both. `write:jira-work` also covers editing and deleting issues; Jira has no classic scope that
   only creates them, and Aegis calls only the create endpoint.

   Don't look for `offline_access` in the console. Aegis adds it to the authorization request, so Atlassian
   also issues a refresh token and the connection outlives the one-hour access token.
4. **Authorization** → **OAuth 2.0 (3LO)** → **Configure**. Set the **Callback URL** to the Aegis page
   Atlassian sends the admin back to:

   ```text
   http://localhost:3000/admin/integrations/jira/callback
   ```

   For a deployed frontend, use its `https://` origin with the same path, and set `AEGIS_JIRA_CALLBACK_URL`
   to exactly the same value.
5. **Settings**: copy the **Client ID** and **Secret**. The secret goes only in your git-ignored `.env`.
6. **Distribution**: integrations are private by default, so only their owner can authorize them. Only the
   org admin who clicks **Connect Jira** ever authorizes; everyone else in that Aegis organization files
   tickets through that one connection. So leave sharing off if you connect Jira yourself. Turn it on only
   when someone else connects their own Atlassian account, and fill in:
   - **Vendor name**: whoever runs this Aegis deployment.
   - **Privacy policy**: `https://<your Aegis address>/privacy`. The page is public and reads two frontend
     settings: `PRIVACY_OPERATOR` (who runs the deployment) and `PRIVACY_CONTACT` (an email address or an
     `https://` link), set in the repo-root `.env`. Atlassian shows the link on the consent screen, so it
     has to open for anyone. An Aegis running only on localhost can't serve it; until it is deployed,
     publish a copy of the page somewhere public, such as a GitHub Gist.
   - **Personal data declaration**: **No**. Aegis stores no Atlassian account IDs, names or email
     addresses, so it has nothing to report through Atlassian's personal data reporting API.

## 2. Configure aegis-core

Add these to the repo-root `.env` (git-ignored). `docker-compose.yml` already passes them to aegis-core:

```sh
AEGIS_JIRA_ENABLED=true
AEGIS_JIRA_CLIENT_ID=<the integration's client ID>
AEGIS_JIRA_CLIENT_SECRET=<the integration's secret>
AEGIS_SECRETS_KEY=<output of: openssl rand -base64 32>
# Only when the frontend is not at http://localhost:3000:
# AEGIS_JIRA_CALLBACK_URL=https://aegis.example.com/admin/integrations/jira/callback
```

Then rebuild: `docker compose up -d --build aegis-core frontend`. When running aegis-core outside compose,
export the same variables: Spring Boot doesn't read `.env`.

`AEGIS_SECRETS_KEY` seals the Jira tokens Aegis stores. Generate it once and keep it. If it changes,
the stored tokens can't be opened, and each organization admin has to connect Jira again. With Jira
enabled, aegis-core refuses to start without a client ID, a secret and a valid 32-byte key.

| Property (`aegis.actions.jira.*`) | Environment variable | Default |
|---|---|---|
| `enabled` | `AEGIS_JIRA_ENABLED` | `false` |
| `client-id` | `AEGIS_JIRA_CLIENT_ID` | Required when enabled |
| `client-secret` | `AEGIS_JIRA_CLIENT_SECRET` | Required when enabled |
| `callback-url` | `AEGIS_JIRA_CALLBACK_URL` | `http://localhost:3000/admin/integrations/jira/callback` |
| `auth-base-url` | `AEGIS_JIRA_AUTH_URL` | `https://auth.atlassian.com` |
| `api-base-url` | `AEGIS_JIRA_API_URL` | `https://api.atlassian.com` |
| `aegis.secrets.key` | `AEGIS_SECRETS_KEY` | Required when enabled |

The Atlassian URLs are fixed configuration, never taken from a request. The tests point them at a local
fake Atlassian server.

## 3. Connect Jira (the organization admin, in Aegis)

1. **Organization → Integrations → Jira → Connect Jira.** Aegis sends you to Atlassian's consent screen,
   which lists the two permissions above. Choose the Jira site and accept.
2. Atlassian sends you back to Aegis, which connects that site. The page says **Connected to *site***.
3. Choose **where risk tickets go**. The project list shows only projects your Atlassian account can
   create issues in. The issue type defaults to *Task* when the project has one. **Save.**

Only an `ORG_ADMIN` connects, changes the project or disconnects. Everyone with an Aegis domain role can
see where tickets go, and a ticket page says what is missing when Jira isn't ready.

## 4. Permissions in Jira itself

Aegis creates issues **as the Atlassian account that connected Jira**, so the issue's reporter is that
account. The account needs Jira access on the site and these permissions in the chosen project. Jira
Cloud's newer interface calls projects *spaces* and issues *work items*:

| Project permission | Newer name | Needed for |
|---|---|---|
| Browse projects | Browse spaces | Reading the project and its issue types |
| Create issues | Create work items | Filing the ticket. The project list shows only projects with this permission |
| Schedule issues | Schedule work items | Setting the due date (optional) |

In a team-managed space, the space's access decides whether that account can file there:

| Space access | Can Aegis file there? |
|---|---|
| Open | Yes: everyone on the site has the Member role |
| Limited | Only if the connecting account was added as Member or Administrator (everyone else is a Viewer) |
| Private | Only if the connecting account was added as Member or Administrator. The account that created the space is its Administrator |

A space the account can't create work items in doesn't appear in Aegis's project list. If the account
loses access later, approvals fail with Jira's reason on the ticket, and once access is restored the
approval can be tried again. Aegis never edits, moves or deletes an issue in any space.

Aegis sets summary, description, issue type, labels, priority and due date. If Jira refuses the priority
or the due date — because the create screen lacks the field, the project uses different priority names,
or the account can't schedule issues — Aegis files the ticket again without both. Aegis keeps them on its
own ticket page either way. Jira's default priority names (Highest, High, Medium, Low, Lowest) are used.

Consider connecting with a dedicated Atlassian account that holds only these permissions, so filed tickets
don't depend on one person's account. It needs a Jira licence on the site.

If your Atlassian organization blocks user-installed apps, the consent screen says so. An Atlassian
organization admin then has to allow it under **Connected apps** in admin.atlassian.com.

## 5. Linking the pull request and closing the ticket

A response's upgrade pull request opens after its ticket is filed, with the ticket's key at the front of its
title and commit message — `SEC-12: Upgrade axios to 1.8.2 in 2 manifests (CVE-…)` — and in its body. That is
all Aegis does in the repository; it never edits, moves or closes the issue.

- **Show the pull request on the issue:** install the free *GitHub for Jira* app on the Jira site and connect
  the GitHub account or organization that owns the repositories. Jira then lists the pull request (its branch
  and commits too) in the issue's Development panel, because its title carries the key.
- **Close the ticket when the fix merges:** in the Jira project, open **Project settings → Automation** and
  create a rule — **When:** *Pull request merged*; **Then:** *Transition issue* to *Done* (optionally only if
  the issue isn't done already). The rule runs as Jira's automation, not as Aegis, so Aegis still never edits
  an issue. Jira's DevOps triggers need Jira Cloud and a connected GitHub cloud account; they don't work with
  GitHub Enterprise Server.

## 6. How Aegis handles the grant

- **Starting a connection:** Aegis generates 32 random bytes as the OAuth `state` and stores only their
  SHA-256. The state is bound to the admin and their organization, lasts ten minutes and works once.
  Coming back with another person's or another organization's state is refused.
- **Tokens:** the access token (one hour) and the refresh token are sealed with AES-256-GCM under
  `AEGIS_SECRETS_KEY`, bound to the organization: a sealed value copied to another organization's row
  won't open. They are never logged or put in error messages.
- **Renewal:** Atlassian's refresh tokens rotate, and each renewal returns a new one. Aegis renews two
  minutes before expiry, under a lock on the organization's connection, so two approvals don't spend the
  same refresh token. If Jira rejects an access token anyway (HTTP 401), Aegis renews it once and retries.
  A refresh token unused for 90 days expires, and then the admin connects again.
- **Sites:** Aegis uses only granted sites whose grant includes `write:jira-work` and whose address is
  `https://`. Every API call goes to `api.atlassian.com` with the site's cloud ID; Aegis never calls the
  site address, it only links to it.

## 7. Disconnecting

In Aegis: **Organization → Integrations → Jira → Disconnect**. Aegis deletes the tokens and the project
settings, and approvals stop filing until someone connects again.

That doesn't withdraw the grant at Atlassian. To do that as well, remove Aegis under **Connected apps** in
the connecting account's settings (https://id.atlassian.com/manage-profile/apps). An Atlassian organization
admin can also remove it under **Connected apps** in admin.atlassian.com.

## 8. Troubleshooting

| What you see | Cause |
|---|---|
| "Jira isn't enabled on this Aegis deployment" | `AEGIS_JIRA_ENABLED` isn't `true` in aegis-core's environment |
| Atlassian shows a scope or redirect error before the consent screen | The integration lacks one of the two scopes, or its callback URL differs from `AEGIS_JIRA_CALLBACK_URL` |
| "This Jira sign-in wasn't started by you in this organization, or it expired; connect again" | More than ten minutes passed, or a different user or organization finished the sign-in |
| "Atlassian didn't accept the sign-in, which may have expired; connect Jira again" | Atlassian refused the code it sent back, usually because it was already used or is too old |
| "That Atlassian account granted Aegis no Jira site it can create issues in; connect again and choose one" | The chosen site isn't a Jira site, or the grant lacks `write:jira-work` |
| "Atlassian granted no refresh token; connect Jira again and allow offline access" | Atlassian didn't issue a refresh token for this grant |
| The project list is empty | The connecting account can't create issues in any project on that site |
| "Choose the Jira project and issue type for risk tickets under Organization → Integrations" | Connected, but no project chosen yet (reconnecting also clears it) |
| "The Jira connection expired or was revoked; an organization admin must connect Jira again" | The refresh token expired (90 days unused) or the grant was removed |
| "Jira refused Aegis's access; an organization admin must connect Jira again" | Jira refused even a freshly renewed access token |
| "A stored credential could not be opened; connect the integration again" | `AEGIS_SECRETS_KEY` changed since the tokens were stored |

A filing that fails is recorded on the ticket with the reason, and it can be approved again once the cause
is fixed.

## 9. Verification

Covered without a real Atlassian site. A local fake Atlassian server plays the token, resource, project and
issue endpoints:

```sh
mvn -B -Dtest='JiraAdapterTest,JiraConfigTest,SecretBoxTest,PackageStructureArchTest' test   # no Docker
mvn -B -Dtest='JiraConnectionStoreIntegrationTest,JiraIntegrationControllerIntegrationTest,RiskTicketIntegrationTest' test
```

The browser flows (connect, choose the project, callback, disconnect, approve and file a ticket) are
covered by the fake-BFF Playwright specs `e2e/jira-integration.spec.ts` and `e2e/risk-tickets.spec.ts`.

Not yet exercised against a real Atlassian site: the consent screen, issue creation in a real project,
rotation over several days, and revocation. Do that with a disposable project.
