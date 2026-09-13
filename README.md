# Aegis

Aegis is an **identity and access platform**: secure authentication with pluggable multi-factor auth,
role-based authorization, self-service accounts, and an append-only audit trail — served through an
API gateway and a Next.js frontend. It is the foundation the wider Aegis platform builds on.

## Architecture

The browser talks only to the Next.js **frontend** (a BFF that keeps tokens in httpOnly cookies). The
BFF calls the **gateway**, which authenticates every request at the edge and routes it to the
**identity-service**:

```
Browser → Frontend + BFF (Next.js) → Gateway (Spring Cloud Gateway) → identity-service (Spring Boot) → PostgreSQL
```

- **identity-service** (Java / Spring Boot) — authentication, users, roles, MFA, and the audit trail;
  issues RS256 JWTs with a published JWKS endpoint.
- **gateway** (Spring Cloud Gateway) — the single entry point; validates JWTs and enforces
  route-level rules, leaving owner-scoped checks to the service.
- **frontend** (Next.js) — marketing page, the full auth flow, account settings, and an admin console.

PostgreSQL (Flyway-migrated), Mailhog (dev SMTP), and Prometheus + Zipkin (metrics + tracing) complete
the stack; all are wired into Docker Compose.

## Features

**Authentication**
- Email + password with **pluggable MFA**, each factor toggled by a single env var:
  - **Email OTP** — 6-digit emailed code; the zero-enrollment baseline.
  - **TOTP** (RFC 6238) — authenticator apps, secrets encrypted at rest, single-use recovery codes.
  - **WebAuthn / passkeys** — device biometrics or a security key (FIDO2).

  When more than one factor is enabled, the user chooses a method at login.
- RS256 JWTs + JWKS (`/oauth2/jwks`); rotating, hashed refresh tokens with per-session and global logout.
- Per-account login lockout and per-IP rate limiting on `/auth/*`.

**Authorization & accounts**
- Platform roles `USER` / `ADMIN` and Aegis domain roles `SERVICE_OWNER` / `ANALYST` /
  `COMPLIANCE_ADMIN`, persisted and issued by identity-service and mapped to Spring `ROLE_*`
  authorities. Platform roles are not aliases for domain roles.
- The opt-in bootstrap account starts with `USER` + `ADMIN`; an administrator assigns domain roles
  through the user-management API before those users access domain APIs.
- Public sign-up toggle (flip to invite-only), admin invite flow, email verification, password reset,
  and self-service change-password — one backend flag drives both the API and the UI.

**Audit trail**
- Append-only log of auth and admin events (who / what / when / client IP / outcome), immutable at the
  database (a trigger rejects updates and deletes), with an admin-only read API.

**Platform**
- PostgreSQL + Flyway migrations; OpenAPI / Swagger UI; Prometheus metrics and Zipkin tracing;
  Testcontainers and Playwright test suites.

## Quick start

```bash
docker compose up --build
```

| Service | URL | Notes |
|---|---|---|
| Frontend | http://localhost:3000 | Next.js app — start here |
| Gateway | http://localhost:8080 | API entry point (JWT auth + routing) |
| identity-service | http://localhost:8081 | API + `/swagger-ui.html` |
| PostgreSQL | localhost:5432 | `identity` / `identity` |
| Mailhog | http://localhost:8025 | dev inbox (SMTP on 1025) |
| Prometheus / Zipkin | http://localhost:9090 · http://localhost:9411 | metrics / traces |

Sign up at http://localhost:3000, then open the **Mailhog inbox** (http://localhost:8025) for the
verification link and the login code. Compose seeds a dev admin — **`admin@example.com` /
`admin-dev-password`** — and enables TOTP so you can try authenticator enrollment in Account settings.

## Configuration

Everything is environment-overridable; the full set of defaults lives in
[`application.yml`](backend/identity-service/src/main/resources/application.yml). Each optional
capability is a single flag, read by the backend and mirrored in the UI via `GET /auth/config`:

| Flag | Default | Effect when off |
|---|---|---|
| `REGISTRATION_ENABLED` | `true` | Invite-only: `POST /auth/register` → 403 and the UI hides sign-up |
| `MFA_EMAIL_OTP_ENABLED` | `true` | Email OTP not offered at login |
| `MFA_TOTP_ENABLED` | `false` | TOTP enrollment hidden / not offered |
| `MFA_WEBAUTHN_ENABLED` | `false` | Passkeys not offered |

Other common knobs: `IDENTITY_BOOTSTRAP_ADMIN_EMAIL` / `…_PASSWORD` (seed an admin),
`AUTH_COOKIE_SECURE` (set `true` behind HTTPS), and the token-TTL, lockout, and rate-limit settings —
all in `application.yml`. Ready-made ops metrics and PromQL are in [`ops/README.md`](ops/README.md).

## Testing

```bash
# Backend — integration tests against a real Postgres (Docker required)
cd backend/identity-service && mvn verify

# Frontend — Playwright end-to-end (real Chromium)
cd frontend && pnpm exec playwright install chromium && pnpm test:e2e
```

Both suites run in CI on every push/PR (`backend-ci`, `frontend-e2e`). The E2E workflow boots
identity-service and the gateway against Postgres + Mailhog, then drives the full sign-up / sign-in
flow through the gateway.

## Roadmap

The wider **Aegis** platform — supply-chain resilience services that build on this identity
foundation — is in progress. Near-term hardening for the platform itself: SSO / OIDC federation (via
the existing delegated-auth seam) and externalized secrets (Vault / KMS).
