# frontend

The Aegis web app: a Next.js 16 UI and the backend-for-frontend (BFF) behind it. The browser talks only to this
server. It keeps the session in httpOnly cookies and calls the API through the gateway on the user's behalf, so a
token never reaches client JavaScript.

## How it reaches the backend

- The browser calls only same-origin routes under `/api`, the route handlers in `src/app/api`. Each reads the
  access token from its cookie and forwards the call to the gateway (`GATEWAY_URL`). The BFF never calls a service
  directly.
- An expired access token is renewed with the refresh cookie and the call retried (`authedBackendFetch` in
  `src/lib/server/api.ts`). A dead session clears both cookies.
- The catch-all handlers (`/api/inventory`, `/api/actions`, `/api/change-signals`, `/api/matching`, `/api/scoring`,
  `/api/integrations`) forward only inside their own prefix, and refuse a path that tries to leave it, such as an
  encoded `../`.
- The browser's address goes on in `X-Forwarded-For` as the load balancer recorded it (`TRUSTED_PROXY_HOPS`), never
  as the browser wrote it, so identity-service's sign-in rate limit sees the real client.
- `src/proxy.ts`, Next.js 16's name for middleware, only checks that a session cookie exists before rendering a
  signed-in page. The backend authorizes every call.
- `GET /auth/config` decides what the sign-in pages offer (sign-up, creating an organization, the second factors),
  so one backend setting drives both the API and the UI.

## Pages

| Group | Routes | Notes |
|---|---|---|
| `(marketing)` | `/`, `/privacy` | Public. The privacy notice reads `PRIVACY_OPERATOR` and `PRIVACY_CONTACT` on each request |
| `(auth)` | `/login`, `/signup`, `/verify-email`, `/forgot-password`, `/reset-password`, `/accept-invite` | Sign-in asks for a second factor: an email code, an authenticator app or a passkey, with a chooser when there are several |
| `(app)` | `/dashboard`, `/inventory/…`, `/advisories`, `/responses/…`, `/findings`, `/reports/…`, `/admin/…`, `/settings` | Signed in. The sidebar shows only what the user's roles allow, and someone in no organization is asked to create or join one first |

In the app:

- **Inventory:** services (their composition, repository link and SBOM import, owners and exposure), components
  and vendors (each row opens **Used by**), and owners.
- **Advisories:** the feed of changes, and each change's impact: the services it reaches, their scores with the
  factors behind them, the evidence, **Draft response** and the DORA regulator notification.
- **Responses:** **Review & approve** (recommended upgrades, then the responses and memos awaiting a decision),
  **History**, and the response, pull-request and memo pages.
- **Admin:** users and their roles, and **Integrations**, where an organization's admin connects GitHub and Jira.
- Findings, Reports and the dashboard are placeholders.

## Code layout

```
src/
  app/(marketing)/, (auth)/, (app)/   pages
  app/api/                            the BFF's route handlers
  components/ui/                      Base UI primitives styled with Tailwind CSS v4
  components/app/                     the inventory, advisories and responses screens
  components/admin/, account/, form/  admin pages, account settings, the sign-in forms
  lib/api.ts                          the browser's client for the BFF
  lib/server/                         server only: the gateway transport, session cookies, auth config
  lib/roles.ts                        which controls a role sees (the backend still decides)
  providers/auth-provider.tsx         useAuth(): the user, their organizations, switchOrg, createOrg
  proxy.ts                            the route guard
e2e/                                  Playwright specs
```

This is Next.js 16 and Base UI, which differ from what most examples assume: read [AGENTS.md](AGENTS.md) and
`node_modules/next/dist/docs/` before changing code. Base UI components take `render={<X />}`, not `asChild`.

## Run it

The whole stack serves this app at http://localhost:3000. From the repository root:

```bash
docker compose up --build
```

To work on the frontend itself, keep the backend running in Compose but stop its frontend container, again from the
repository root:

```bash
docker compose stop frontend
```

Then, from this folder, with Node 22 and pnpm:

```bash
pnpm install
```

```bash
pnpm dev
```

The dev server serves http://localhost:3000 and calls the gateway that Compose publishes at http://localhost:8080.

## Configuration

| Variable | Default | What it does |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` | Where the BFF sends every backend call. Server side only |
| `AUTH_COOKIE_SECURE` | `false` | Marks the session cookies `Secure`. Set it to `true` behind HTTPS, and leave it off on `http://localhost`, where browsers drop Secure cookies |
| `TRUSTED_PROXY_HOPS` | `1` | How many proxies in front of this server append to `X-Forwarded-For`: 1 for a load balancer |
| `PRIVACY_OPERATOR`, `PRIVACY_CONTACT` | empty | Who runs this deployment, and an email address or link to reach them, shown on `/privacy` |

## Checks and tests

| Check | Command | Needs |
|---|---|---|
| Types | `pnpm exec tsc --noEmit` | Nothing else |
| Lint | `pnpm lint` | Nothing else |
| Build | `pnpm build` | Nothing else |
| End to end | `pnpm test:e2e` | identity-service, the gateway and Mailhog |

The end-to-end suite runs 74 Playwright tests in Chromium. Sign-up, sign-in, organizations and the admin pages run
against the real identity-service and gateway, reading the emailed sign-in codes from Mailhog. The other specs need
no aegis-core: the estate and response pages are checked against a fake BFF in the browser, and aegis-core's own
tests cover its rules.

To run it locally, install the browser once:

```bash
pnpm exec playwright install chromium
```

Start the stack from the repository root and stop its frontend, so that Playwright starts `pnpm dev` from your
working tree instead of testing the container's build:

```bash
docker compose up -d --build
```

```bash
docker compose stop frontend
```

Then run the suite from this folder:

```bash
pnpm test:e2e
```

Only one spec may sign in as the seeded admin, `admin@example.com`, because concurrent sign-ins race for its emailed
codes. CI runs the suite in `frontend-e2e`, against identity-service and the gateway built from source.

## Image

The [Dockerfile](Dockerfile) builds Next.js's standalone output and runs it as a non-root user on port 3000.
