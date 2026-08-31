# identity-service

A **universal user-management & authentication service** — the identity core of **Aegis**. It owns user profile CRUD and
role-based access, and issues its own JWTs — but is built around an **OIDC "auth seam"** so
the same app can instead validate tokens from an external provider (Cognito, Supabase,
Keycloak, …) by changing one config value, with no code changes.

## Stack

Java 21 · Spring Boot 4 · Spring Security 7 (OAuth2 resource server + JOSE) ·
Spring Data JPA · PostgreSQL · Flyway · springdoc (Swagger UI) · Docker Compose.

## Quickstart

This service is wired into the repository's root `docker-compose.yml`. You need **Docker** only —
the app is compiled inside a Maven container, so no local Maven/JDK is required. From the
**repo root**:

```bash
docker compose up --build
```

The identity service is then on **:8081** (Compose also starts Postgres, the frontend, Mailhog,
Zipkin and Prometheus — see the root [README](../../README.md)):

- API: `http://localhost:8081`
- Swagger UI: `http://localhost:8081/swagger-ui.html`
- JWKS (public keys): `http://localhost:8081/oauth2/jwks`

### Try the flow

With email-OTP on (the default), **login returns an MFA challenge, not tokens** — you exchange a
6-digit code for tokens at `/auth/login/verify-otp`. With no SMTP configured the code is written
to the app log (the console `EmailSender`); under Compose it lands in the Mailhog inbox
(http://localhost:8025).

```bash
# register (role USER; needs REGISTRATION_ENABLED=true, the default)
curl -sX POST localhost:8081/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123","displayName":"Alice"}'

# login -> { mfaRequired: true, challengeId, method: "EMAIL_OTP", ... }   (code emailed/logged)
curl -sX POST localhost:8081/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'

# verify the code -> { accessToken, refreshToken, tokenType, expiresInSeconds }
curl -sX POST localhost:8081/auth/login/verify-otp -H 'Content-Type: application/json' \
  -d '{"challengeId":"<CHALLENGE_ID>","code":"<6-DIGIT-CODE>"}'

# call a protected endpoint
curl -s localhost:8081/users/me -H "Authorization: Bearer <ACCESS_TOKEN>"
```

For a password-only flow while testing, set `MFA_EMAIL_OTP_ENABLED=false` and login returns
tokens directly.

### Getting an admin

Admin endpoints require the `ADMIN` role. An initial admin is **seeded from config on startup** —
**opt-in** (no default admin unless configured) and **idempotent** (creates the user if missing,
grants ADMIN if not already held). This is the same pattern as Keycloak (`KEYCLOAK_ADMIN`) and
Grafana (`GF_SECURITY_ADMIN_*`); the password is used only when creating a new admin and is never
logged. `docker compose up` already sets dev credentials:

```
IDENTITY_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
IDENTITY_BOOTSTRAP_ADMIN_PASSWORD=admin-dev-password   # dev only — inject a secret in prod
```

## API

The always-current endpoint reference is the **Swagger UI** at
`http://localhost:8081/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`). It covers registration
& login, the MFA challenge + `verify-otp` and method selection, TOTP enrollment and recovery
codes (`/auth/mfa/*`), email verification, password reset, admin user provisioning / invites
(`POST /users`), the user CRUD & roles API, and the admin audit trail (`/audit`). Other services
verify tokens against the public keys at `GET /oauth2/jwks`.

## The auth seam (how it's "dynamic")

Two config values control it — everything else is identical in both modes:

| Var | Default | Meaning |
|---|---|---|
| `IDENTITY_AUTH_MODE` | `local` | `local` = issue & validate our own JWTs · `delegated` = validate an external OIDC issuer |
| `IDENTITY_ISSUER_URI` | *(empty)* | External OIDC issuer URL, required when `mode=delegated` |

- **local** — `JwtConfig` builds a `JwtEncoder` + `JwtDecoder` from an RSA key and publishes the
  public key at `/oauth2/jwks`. Self-contained, zero external dependencies.
- **delegated** — `JwtDecoder` is built from the provider's `issuer-uri`; the app stops issuing
  tokens and simply validates the provider's. Profile CRUD is unchanged.

## Configuration

Every setting is environment-overridable. The authoritative list with defaults lives in
[`application.yml`](src/main/resources/application.yml), and the root
[README](../../README.md#configuration) documents the full env-var surface — datasource, the auth
seam, token TTLs, the MFA & registration feature flags, rate-limiting, audit, tracing and mail.
(The two auth-seam knobs above are the ones that change how tokens are produced/validated.)

## Layout

```
src/main/java/com/usermgmt/identity/
  config/     SecurityConfig · JwtConfig (the seam) · JwksController · OpenApiConfig
  auth/       AuthController · AuthService · AccountService · MfaService · login, refresh,
              email verification & reset, challenges · EmailSender (+ repositories)
  auth/mfa/   TOTP enrollment & verification · recovery codes · secret encryption
  user/       User · Role · UserService · UserController · UserDtos (+ repository)
  audit/      append-only security audit trail + admin read API
  ratelimit/  per-IP token-bucket filter over /auth/*
  common/     ApiError · ApiExceptions · GlobalExceptionHandler · Tokens
  bootstrap/  AdminBootstrapRunner
src/main/resources/  application.yml · db/migration/ (Flyway V1…Vn)
src/test/java/…      AbstractIntegrationTest + integration tests across the above
```

## Security notes (prototype)

- Passwords hashed with bcrypt (`DelegatingPasswordEncoder`).
- Refresh tokens are opaque, stored **hashed** (SHA-256), and **rotated** (single-use) on exchange.
- The RSA signing key is **generated at startup** (ephemeral). For production, load a stable key
  from a secret manager / keystore so tokens survive restarts and can be rotated deliberately.
- **Account lockout:** repeated failed logins lock the account (HTTP 423) for a cooldown.
- **Email verification & password reset** use single-use, hashed, time-limited tokens. The default
  `EmailSender` **logs** the token (dev only); supply an SMTP-backed `EmailSender` bean for production.
- **TOTP secrets are encrypted at rest** (AES-256-GCM); the passphrase (`MFA_TOTP_SECRET_KEY`) is
  a dev-only default — inject a real secret in production.
- Forgot-password never reveals whether an email exists (always the same response).

## Testing

Integration tests boot the full application against a **real PostgreSQL** via Testcontainers, so
Flyway migrations, Hibernate schema validation and the Spring Security filter chain are all
genuinely exercised. They require a running Docker daemon.

```
# from backend/identity-service (needs Maven + Docker)
mvn verify
```

CI runs them on every push/PR via `.github/workflows/backend-ci.yml` (JDK 21 + Docker on the
GitHub runner), so they stay green without a local Maven install.
