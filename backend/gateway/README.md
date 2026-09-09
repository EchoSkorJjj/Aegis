# Aegis Gateway

The single entry point for Aegis, built on **Spring Cloud Gateway (WebMVC)**. It authenticates
requests at the edge and applies coarse, route-level authorisation, then forwards to the right
backend service. Fine-grained, owner-scoped authorisation stays in each service — see the root
[`ARCHITECTURE.md`](../../ARCHITECTURE.md).

> **Status: scaffold (verified boots).** Compiles and the full Spring context starts (routes +
> servlet resource-server security + actuator). The route table below is intentionally minimal; it
> is expanded when the frontend BFF is cut over to the gateway (see *Cutover* below).

## Why WebMVC (servlet), not WebFlux

Spring Cloud Gateway ships two flavours; this uses `spring-cloud-starter-gateway-server-webmvc`
(Tomcat, servlet, functional `RouterFunction` routes). It matches the imperative stack the other
services use and reuses the **same** Spring Security servlet resource-server model
(`identity-service` and `aegis-core`), so JWT validation and role mapping are written the way the
team already knows. The reactive flavour is `spring-cloud-starter-gateway-server-webflux`.

## Version

- Spring Boot **4.1.1** (parent).
- Spring Cloud **2025.1.2** ("Oakwood") — the release train built on Spring Framework 7 / Spring
  Boot 4. Bump `spring-cloud.version` in `pom.xml` in step with the Boot parent.

## Auth model

- **Here (authentication + coarse authorisation):** validate the JWT against identity-service's JWKS
  (`IDENTITY_JWKS_URI`; Boot builds a lazy decoder, so startup never blocks on identity-service),
  map the `roles` claim to `ROLE_*`, and enforce route-level rules — e.g. `/api/admin/**` requires
  `ADMIN`; `/auth/**` and `/oauth2/**` are public pass-through.
- **Downstream (fine-grained authorisation):** each service decides "can this user act on *this*
  finding or service?" The gateway cannot know that.

## Routes

Defined functionally in [`RouteConfig`](src/main/java/com/usermgmt/gateway/RouteConfig.java); paths
are forwarded unchanged.

| Path | Downstream |
|---|---|
| `/auth/**`, `/oauth2/**` | identity-service (`gateway.identity-uri`) |
| `/api/**` | aegis-core (`gateway.core-uri`) |

Identity-service also serves other paths (e.g. `/users/**`) that are **not** yet routed here; add
them before pointing the BFF at the gateway.

## Environment variables

| Var | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | Gateway port (the front door) |
| `IDENTITY_URI` | `http://localhost:8081` | Downstream identity-service base URI |
| `CORE_URI` | `http://localhost:8082` | Downstream aegis-core base URI |
| `IDENTITY_JWKS_URI` | `http://localhost:8081/oauth2/jwks` | Keys to validate access tokens |
| `ZIPKIN_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Trace collector |

## Build & run

```bash
# from backend/gateway
mvn clean test          # compiles + context-load test (no Docker needed — the gateway has no DB)
mvn spring-boot:run     # run locally on :8080
```

## Cutover (later)

The gateway is wired into `docker-compose.yml` and runs on `:8080`, but the frontend BFF still calls
`identity-service` directly. To route the browser path through the gateway: add routes for
identity-service's full surface (above), then set the frontend's `IDENTITY_API_URL` to
`http://gateway:8080`. Do it as one deliberate step so a missing route can't silently break login.
