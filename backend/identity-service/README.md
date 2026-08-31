# identity-service

A **universal user-management & authentication service**. It owns user profile CRUD and
role-based access, and issues its own JWTs — but is built around an **OIDC "auth seam"** so
the same app can instead validate tokens from an external provider (Cognito, Supabase,
Keycloak, …) by changing one config value, with no code changes.

> Design principle: **authentication is pluggable; the user profile store is always yours.**
> Even when login is delegated to an external provider, the `users` table, roles and CRUD
> stay in this service, keyed by the token subject.

## Stack

Java 21 · Spring Boot 4 · Spring Security 7 (OAuth2 resource server + JOSE) ·
Spring Data JPA · PostgreSQL · Flyway · springdoc (Swagger UI) · Docker Compose.

## Quickstart

You need **Docker** only — the app is compiled inside a Maven container, so no local Maven/JDK
is required.

```bash
docker compose up --build
```

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- JWKS (public keys): `http://localhost:8080/oauth2/jwks`

### Try the flow

```bash
# register (first user gets role USER)
curl -sX POST localhost:8080/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123","displayName":"Alice"}'

# login -> { accessToken, refreshToken, tokenType, expiresInSeconds }
curl -sX POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'

# call a protected endpoint
curl -s localhost:8080/users/me -H "Authorization: Bearer <ACCESS_TOKEN>"
```

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

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `/auth/register` | public | Create account (role `USER`) |
| POST | `/auth/login` | public | Exchange credentials for tokens |
| POST | `/auth/refresh` | public | Rotate refresh token → new tokens |
| POST | `/auth/logout` | public | Revoke a presented refresh token |
| POST | `/auth/logout-all` | authenticated | Revoke all of the caller's refresh tokens |
| POST | `/auth/verify-email` | public | Verify an email with a token |
| POST | `/auth/resend-verification` | public | Re-send a verification token |
| POST | `/auth/forgot-password` | public | Request a password-reset token |
| POST | `/auth/reset-password` | public | Set a new password with a reset token |
| GET | `/users/me` | authenticated | Current user's profile |
| GET | `/users` | `ADMIN` | List users (paged) |
| GET | `/users/{id}` | `ADMIN` or self | Fetch a user |
| PUT | `/users/{id}` | `ADMIN` or self | Update profile (`enabled` is admin-only) |
| PUT | `/users/{id}/roles` | `ADMIN` | Set a user's roles |
| DELETE | `/users/{id}` | `ADMIN` | Delete a user |
| GET | `/oauth2/jwks` | public | Public keys for other services to verify tokens |

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

| Var | Default | Notes |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/identity` / `identity` / `identity` | Datastore |
| `IDENTITY_ISSUER` | `http://localhost:8080` | `iss` claim on issued tokens |
| `ACCESS_TTL` | `900` | Access-token lifetime (seconds) |
| `REFRESH_TTL` | `604800` | Refresh-token lifetime (seconds) |
| `MAX_FAILED_LOGINS` / `LOCKOUT_MINUTES` | `5` / `15` | Failed logins before lockout, and lockout duration |
| `EMAIL_VERIFICATION_HOURS` / `PASSWORD_RESET_MINUTES` | `24` / `30` | Single-use token lifetimes |
| `IDENTITY_BOOTSTRAP_ADMIN_EMAIL` | *(empty)* | Seed/promote this admin on startup (opt-in) |
| `IDENTITY_BOOTSTRAP_ADMIN_PASSWORD` | *(empty)* | Password for a newly-created bootstrap admin |

## Layout

```
src/main/java/com/usermgmt/identity/
  config/    SecurityConfig · JwtConfig (the seam) · JwksController · OpenApiConfig
  auth/      AuthController · AuthService · AccountService · LoginAttemptService · EmailSender
             · RefreshToken · AuthToken · AuthDtos (+ repositories)
  user/      User · Role · UserRepository · UserService · UserController · UserDtos
  common/    ApiError · ApiExceptions · GlobalExceptionHandler · Tokens
  bootstrap/ AdminBootstrapRunner
src/main/resources/  application.yml · db/migration/V1__init … V3__account_tokens.sql
src/test/java/…      AbstractIntegrationTest + auth/user/bootstrap integration tests
```

## Security notes (prototype)

- Passwords hashed with bcrypt (`DelegatingPasswordEncoder`).
- Refresh tokens are opaque, stored **hashed** (SHA-256), and **rotated** (single-use) on exchange.
- The RSA signing key is **generated at startup** (ephemeral). For production, load a stable key
  from a secret manager / keystore so tokens survive restarts and can be rotated deliberately.
- **Account lockout:** repeated failed logins lock the account (HTTP 423) for a cooldown.
- **Email verification & password reset** use single-use, hashed, time-limited tokens. The default
  `EmailSender` **logs** the token (dev only); supply an SMTP-backed `EmailSender` bean for production.
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

## Roadmap

- **Phase 2** — ✅ complete: admin bootstrap · Testcontainers tests · logout & refresh revocation ·
  login rate-limit / lockout · email verification · password reset.
- **Phase 3** — wire the `delegated` OIDC mode against a live provider (Keycloak/Supabase); profile
  sync from claims.
- **Phase 4** — production hardening, cloud deploy, service-to-service (client-credentials) so the
  Aegis backend can call this service.
