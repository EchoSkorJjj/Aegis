# Aegis

**Aegis** is a resilience platform that reads a changing threat landscape and decides what a
bank should do about it. It continuously ingests real-world change — new vulnerability
advisories, vendor outages, security news — and works out whether and why each change matters
to a bank's specific map of business services and software dependencies. It scores impact per
service and per owner, then agentically drafts the response — a safe-upgrade pull request, a
risk ticket, a regulator-notification memo — that a human risk owner approves or overrides.

---

## Features

**Authentication**
- Email + password login with **pluggable multi-factor auth** — each factor
  independently on/off via a single env var:
  - **Email OTP** — emailed 6-digit code; the zero-enrollment baseline / fallback.
  - **TOTP authenticator apps** (RFC 6238) — QR-code enrollment, per-user opt-in,
    secrets **encrypted at rest** (AES-256-GCM), with single-use **recovery codes**.
  - **WebAuthn / passkeys** — scaffolded, off by default.

  When more than one factor is enabled, the user **chooses a method at login**.
- RS256-signed JWTs with a published **JWKS** endpoint (`/oauth2/jwks`).
- **Rotating, hashed refresh tokens**; per-session and global (`logout-all`) revocation.
- **Login lockout** (per-account) plus per-IP **rate limiting** (token bucket) across `/auth/*`.
- An **auth seam**: run `local` (this service mints its own JWTs) or `delegated`
  (validate an external OIDC provider's tokens) with a config switch, no code change.

**Authorization**
- `USER` / `ADMIN` roles mapped to Spring Security authorities.
- Method-level RBAC (`@PreAuthorize`) on the `/users` API — admin-wide vs. owner-only.
- Opt-in **admin bootstrap** that idempotently seeds an initial admin on startup.

**Account & email**
- **Public registration on/off** — one backend flag flips the whole app to **invite-only**:
  the API rejects self-registration (403) and the SPA hides every sign-up affordance
  (see [Feature toggles](#feature-toggles)).
- **Admin invite flow** — an admin provisions a user by email; an invite link is emailed;
  the user sets their first password, and their email is **auto-verified** on accept
  (holding the invited inbox is the verification).
- Email verification and single-use, hashed **password-reset** and **invite** tokens.
- Self-service **change password** for signed-in users.
- Pluggable `EmailSender`: real SMTP when configured, console-logging fallback otherwise.
- **Mailhog** wired into Compose so you can read OTP / verification / reset / invite mail locally.

**Audit trail**
- Append-only **security audit log** of authentication and admin events — who, what,
  when, client IP, and outcome — written in its own transaction so it survives rollback.
- Immutability enforced at the **database** (a trigger rejects any update/delete), not just in code.
- Admin-only, filterable read API (`GET /audit`).

**Front end**
- Next.js **App Router** with a marketing page, full auth flow, and an app shell.
- **BFF + httpOnly cookies** — access/refresh tokens never touch client JavaScript.
- **Server-side route guard** (`proxy.ts`) redirects before render, from the session cookie.
- Two-step login with a **method chooser**, forgot/reset password, verify-email, and
  invite-acceptance screens — all driven by `GET /auth/config`, so one backend flag drives the UI.
- **Account settings** — profile, email-verification status, change password, and
  **self-service TOTP enrollment** (QR code + recovery codes).
- **Admin console** — list/search users, manage roles, and **invite new users**.

**Foundations**
- PostgreSQL with **Flyway** migrations; Hibernate validates (never mutates) the schema.
- **Testcontainers** integration tests against a real Postgres.
- **OpenAPI / Swagger UI** and actuator health out of the box.
- **Observability** — Prometheus metrics at `/actuator/prometheus` (including custom
  security counters) and distributed tracing (Micrometer → Zipkin); Prometheus and
  Zipkin are wired into Compose.

---

## Feature toggles

Every optional capability is a **single environment variable**, read by the backend at
startup. The backend is the source of truth and the enforcer; the SPA reads the same state
from `GET /auth/config` and renders to match — so **one flag controls both halves**, with no
separate frontend variable or build to keep in sync.

| Capability | Flag | Default | When off |
| --- | --- | --- | --- |
| Public sign-up | `REGISTRATION_ENABLED` | `true` | `POST /auth/register` → **403**; SPA hides sign-up (invite-only) |
| Email-OTP 2FA | `MFA_EMAIL_OTP_ENABLED` | `true` | Not offered as a login factor |
| TOTP authenticator | `MFA_TOTP_ENABLED` | `false` | Enrollment hidden; not offered at login |
| WebAuthn / passkeys | `MFA_WEBAUTHN_ENABLED` | `false` | Not offered (scaffold) |

With **no** MFA factor enabled, login is password-only. Email OTP needs no enrollment;
TOTP and WebAuthn are opt-in per user once their factor is turned on. Full knobs (code
length, TTLs, TOTP period/digits, recovery-code count) are in [Configuration](#configuration).

---

## Quick start (Docker)

```bash
docker compose up --build
```

Brings up the full stack:

| Service    | URL / port                                   | Notes                                 |
| ---------- | -------------------------------------------- | ------------------------------------- |
| Front end  | http://localhost:3000                        | Next.js app                           |
| Identity   | http://localhost:8081                        | API + `/swagger-ui.html`              |
| PostgreSQL | localhost:5432                               | `identity` / `identity`               |
| Mailhog    | http://localhost:8025 (SMTP on 1025)         | web inbox for all outgoing mail       |
| Zipkin     | http://localhost:9411                        | distributed traces                    |
| Prometheus | http://localhost:9090                        | scrapes `/actuator/prometheus`        |

**Try the flow:** sign up at http://localhost:3000, then open the **Mailhog inbox**
(http://localhost:8025) to read the verification link and the login OTP. Compose also
seeds a dev admin — **`admin@example.com` / `admin-dev-password`** (override or remove
before any real deployment). Compose enables TOTP (`MFA_TOTP_ENABLED=true`) so you can try
authenticator enrollment from **Account settings**; flip `REGISTRATION_ENABLED=false` to
see invite-only mode.

---

## Configuration

Everything is environment-overridable (defaults in
[`application.yml`](backend/identity-service/src/main/resources/application.yml)).

**Identity service**

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `…/identity`, `identity`, `identity` | Datasource |
| `IDENTITY_AUTH_MODE` | `local` | `local` (self-issued JWTs) or `delegated` (external OIDC) |
| `IDENTITY_ISSUER` / `IDENTITY_ISSUER_URI` | `http://localhost:8081` / — | Token issuer; `issuer-uri` used when delegated |
| `ACCESS_TTL` / `REFRESH_TTL` | `900` / `604800` | Access & refresh token lifetimes (seconds) |
| `REGISTRATION_ENABLED` | `true` | Public self-registration; `false` = invite-only (register → 403) |
| `MFA_EMAIL_OTP_ENABLED` | `true` | Offer emailed-OTP as a login factor |
| `MFA_EMAIL_OTP_LENGTH` / `…_TTL_MINUTES` / `…_MAX_ATTEMPTS` | `6` / `10` / `5` | Email-OTP code shape & limits |
| `MFA_TOTP_ENABLED` | `false` | Offer TOTP authenticator apps (per-user opt-in) |
| `MFA_TOTP_ISSUER` / `…_PERIOD` / `…_DIGITS` / `…_WINDOW` | `Identity` / `30` / `6` / `1` | TOTP label, step seconds, digits, drift steps tolerated |
| `MFA_TOTP_SECRET_KEY` | `dev-only-…` | Passphrase encrypting stored TOTP secrets — **change in production** |
| `MFA_WEBAUTHN_ENABLED` | `false` | Offer WebAuthn / passkeys (scaffold) |
| `MFA_RECOVERY_CODES_COUNT` | `10` | Backup codes issued when TOTP is enabled |
| `MAX_FAILED_LOGINS` / `LOCKOUT_MINUTES` | `5` / `15` | Per-account login lockout policy |
| `RATE_LIMIT_ENABLED` | `true` | Per-IP token-bucket throttle on `/auth/*` |
| `RATE_LIMIT_CAPACITY` / `RATE_LIMIT_REFILL_SECONDS` | `20` / `60` | Burst size, and window to refill it (per IP, per endpoint) |
| `AUDIT_RECORD_TOKEN_REFRESH` | `true` | Record the frequent token-refresh event in the audit trail |
| `TRACING_SAMPLE_RATE` | `1.0` | Fraction of requests traced (`0` disables tracing) |
| `ZIPKIN_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Where trace spans are sent |
| `EMAIL_VERIFICATION_HOURS` / `PASSWORD_RESET_MINUTES` / `INVITE_HOURS` | `24` / `30` / `72` | Single-use token lifetimes (verify / reset / invite) |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` | — | Enable real SMTP (e.g. Mailhog) |
| `IDENTITY_FRONTEND_BASE_URL` / `IDENTITY_MAIL_FROM` | `http://localhost:3000` / `no-reply@identity.local` | Email link base & sender |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Allowed SPA origins |
| `IDENTITY_BOOTSTRAP_ADMIN_EMAIL` / `…_PASSWORD` | — | Seed an initial admin (opt-in) |

**Front end**

| Variable | Default | Purpose |
| --- | --- | --- |
| `IDENTITY_API_URL` | `http://localhost:8081` | Server-side BFF → identity service (use the service name in Compose) |
| `AUTH_COOKIE_SECURE` | `false` | Mark session cookies `Secure`. Keep `false` on `http://localhost` (Safari drops Secure cookies over plain HTTP); set `true` behind HTTPS |

The BFF forwards the caller's `X-Forwarded-For` to the identity service, so the audit trail
and rate limiter attribute the real client IP (behind a proxy / load balancer that sets it).

---

## API overview

Full, interactive docs at `http://localhost:8081/swagger-ui.html`.

---

## Testing

```bash
# Backend integration tests — real Postgres via Testcontainers (Docker required)
cd backend/identity-service && ./mvnw verify
```

Front-end end-to-end tests (Playwright, real Chromium — exercises the server-side guard,
the httpOnly-cookie BFF, and the Base UI components a headless DOM can't):

```bash
cd frontend
pnpm exec playwright install chromium   # one-time browser download
pnpm test:e2e
```

The E2E suite expects the backend on :8081 (email-OTP on, the default) with the bootstrap admin
seeded, and Mailhog at :8025 — it reads the emailed login codes to complete the OTP step.
The front-end dev server is started/reused automatically. Both suites also run in CI on every
push/PR (`backend-ci`, `frontend-e2e`). See
[`frontend/playwright.config.ts`](frontend/playwright.config.ts).

---

## Roadmap

- **WebAuthn / passkeys** — finish the scaffolded factor (registration + assertion ceremonies).
- **Single sign-on** — wire a real OIDC provider into the existing delegated-auth seam.
- **Secrets management** — externalize the JWT signing key and TOTP passphrase (e.g. Vault / KMS).
