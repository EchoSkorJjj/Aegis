# Aegis Gateway

Aegis's single entry point, built on **Spring Cloud Gateway (WebMVC)**. It authenticates every request at the edge,
applies the coarse route rules, and forwards the request to identity-service or aegis-core. Each service then checks
the token again and makes the fine-grained decisions the gateway can't know, such as who may act on which service.
Only the frontend's BFF calls it; the browser never does.

## Routes

Defined in [`RouteConfig`](src/main/java/com/aegis/gateway/RouteConfig.java) and guarded by
[`SecurityConfig`](src/main/java/com/aegis/gateway/SecurityConfig.java). Paths are forwarded unchanged.

| Path | Goes to | Needs |
|---|---|---|
| `/auth/**`, `/oauth2/**` | identity-service | Nothing: sign-up, sign-in, token refresh and the JWKS are public |
| `/users/**`, `/orgs/**` | identity-service | A valid token |
| `/api/admin/**` | aegis-core | `PLATFORM_ADMIN` |
| `/api/**` | aegis-core | A valid token |
| `/actuator/health`, `/actuator/info`, `/actuator/prometheus` | the gateway itself | Nothing |

ai-service has no route: only aegis-core reaches it. identity-service's `/audit` (the security log) isn't routed yet.

## Authentication

- Validates RS256 access tokens against identity-service's JWKS (`IDENTITY_JWKS_URI`). Boot builds the decoder
  lazily, so the gateway starts even while identity-service is down.
- Maps the token's `roles` claim to `ROLE_*` authorities, the same way identity-service and aegis-core do.
- Stateless: there's no session, and CSRF protection is off because the BFF sends a bearer token, never a cookie.

## Client addresses (X-Forwarded-For)

identity-service's `/auth` rate limit and audit log key on the first `X-Forwarded-For` hop. The frontend's BFF sets it
to the browser's address (the one address its load balancer recorded, never what the browser wrote).
[`ForwardedForFilter`](src/main/java/com/aegis/gateway/ForwardedForFilter.java) passes that header on, with the
caller's own address appended, only when the caller matches `gateway.trusted-proxies` (the BFF's network); from
anyone else it sends just their own address. Spring Cloud Gateway's own X-Forwarded filter stays off: it keeps an
entry only when the entry itself is a trusted proxy, which would drop the browser's public address. Without either,
the gateway strips the header and every user shares one rate-limit bucket.

## Why WebMVC (servlet), not WebFlux

Spring Cloud Gateway ships two flavours; this uses `spring-cloud-starter-gateway-server-webmvc` (Tomcat, servlet,
functional `RouterFunction` routes). It matches the imperative stack the other services use and reuses the **same**
Spring Security servlet resource-server model as identity-service and aegis-core, so JWT validation and role mapping
are written the way the team already knows. The reactive flavour is `spring-cloud-starter-gateway-server-webflux`.

## Version

- Spring Boot **4.1.1** (parent).
- Spring Cloud **2025.1.2** ("Oakwood"), the release train built on Spring Framework 7 / Spring Boot 4. Bump
  `spring-cloud.version` in `pom.xml` in step with the Boot parent.

## Client addresses (X-Forwarded-For)

identity-service's `/auth` rate limit and audit log key on the first `X-Forwarded-For` hop. The frontend's BFF
sets it to the browser's address (the one address its load balancer recorded, never what the browser wrote).
[`ForwardedForFilter`](src/main/java/com/aegis/gateway/ForwardedForFilter.java) passes that header on, with the
caller's own address appended, only when the caller matches `gateway.trusted-proxies` (the BFF's network); from
anyone else it sends just their own address. Spring Cloud Gateway's own X-Forwarded filter stays off: it keeps an
entry only when the entry itself is a trusted proxy, which would drop the browser's public address. Without either,
the gateway strips the header and every user shares one rate-limit bucket.

## Environment variables

| Var | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | Gateway port (the front door) |
| `IDENTITY_URI` | `http://localhost:8081` | identity-service's base URI |
| `CORE_URI` | `http://localhost:8082` | aegis-core's base URI |
| `IDENTITY_JWKS_URI` | `http://localhost:8081/oauth2/jwks` | Keys to validate access tokens |
| `GATEWAY_TRUSTED_PROXIES` | private and loopback addresses | Java regex over the caller's IP: whose `X-Forwarded-For` is passed on (the BFF's subnets) |
| `ZIPKIN_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Trace collector |
| `TRACING_SAMPLE_RATE` | `1.0` | Share of requests traced |

## Build and test

From this folder:

```bash
mvn verify
```

That runs the tests (the application context, and the forwarded-for rules against a stub identity-service) and the
formatting check; it needs no database or Docker. `mvn spotless:apply` fixes the formatting. To run the gateway on
:8080, forwarding to identity-service on :8081 and aegis-core on :8082:

```bash
mvn spring-boot:run
```

CI runs `mvn verify` in `gateway-ci` on every push and pull request that touches this folder. Docker Compose runs the
gateway as `gateway`; on AWS it's an ECS service that only the frontend can reach.
