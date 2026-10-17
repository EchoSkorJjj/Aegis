# AWS deployment — settings to build into the Terraform

Fixes 1–5 are done in code. The items below are settings the Terraform in `terraform/` sets, plus two one-time
steps outside it. The plan they fit: ECS on Fargate behind one ALB, RDS PostgreSQL 16, a NAT instance, the domain
**aegisops.pro**.

Aegis serves at `https://aegisops.pro`. Only the frontend is public: the browser never calls the gateway (only the
frontend's BFF does), so the gateway, identity-service, aegis-core and ai-service stay in private subnets.

## 6. JVM heap — ECS task definitions

The Java images run `java -jar app.jar` with no flags, so the JVM caps its heap at 25% of the container's memory
(256 MB in a 1 GB task). Set on each Java container:

| Container | `JAVA_TOOL_OPTIONS` | Task size to start |
|---|---|---|
| gateway | `-XX:MaxRAMPercentage=70` | 0.5 vCPU / 1 GB |
| identity-service | `-XX:MaxRAMPercentage=70` | 0.5 vCPU / 1 GB |
| aegis-core | `-XX:MaxRAMPercentage=60` (git and syft run beside the JVM) | 1 vCPU / 2 GB |

Frontend 0.25 vCPU / 0.5 GB, ai-service 0.25 vCPU / 0.5 GB. Keep this out of the Dockerfiles: in docker compose
the containers have no memory limit, so a percentage there would be of Docker's whole VM, for each of three JVMs.

## 7. Long requests — ALB idle timeout and the frontend's keep-alive

- `idle_timeout = 300` on the ALB. Importing an SBOM from a repository is one request that can run up to
  4.5 minutes (`AEGIS_SBOM_CLONE_TIMEOUT` 90 s + `AEGIS_SBOM_SCAN_TIMEOUT` 180 s); the default 60 s cuts it off
  with a 504. Nothing to change in code: the BFF and the gateway already hold a request that long.
- `KEEP_ALIVE_TIMEOUT=305000` (milliseconds) on the frontend task, longer than the ALB's idle timeout. Next.js's
  standalone server reads it. Without it Node closes idle connections after 5 s while the ALB still counts them
  open, and users get occasional 502s.
- CloudFront, if added later: its origin response timeout is 30 s by default and 60 s without a quota request
  (180 s with one), still shorter than the longest import. Make the import asynchronous first, or keep CloudFront out.

## 8. Email — Amazon SES

Email codes are the default second sign-in factor, so SES has to work on day one. identity-service sends through
SMTP whenever `SPRING_MAIL_HOST` is set (`SmtpEmailSender`); without it the codes only go to its log.

Terraform:
- `aws_sesv2_email_identity` for `aegisops.pro` with Easy DKIM, and its three DKIM CNAME records in Route 53.
- Optional but better deliverability: a custom MAIL FROM domain `mail.aegisops.pro` (MX + SPF TXT) and a DMARC
  record `_dmarc.aegisops.pro` = `v=DMARC1; p=none;`.
- SMTP credentials: SES SMTP logins come from an IAM user's access key (a task role can't sign SMTP). An IAM user
  allowed only `ses:SendRawEmail`; its `aws_iam_access_key` exposes `ses_smtp_password_v4`. Put the key id and that
  password in SSM SecureStrings. (The password lands in Terraform state; alternatively create the key by hand.)
- identity-service environment:
  - `SPRING_MAIL_HOST=email-smtp.<region>.amazonaws.com`, `SPRING_MAIL_PORT=587`
  - `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` from SSM
  - `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`
  - `IDENTITY_MAIL_FROM=no-reply@aegisops.pro`
  - Send a test email after the first deploy to confirm the TLS settings took.

Outside Terraform: SES starts in the sandbox, which only delivers to verified addresses (200 a day). Request
production access in the SES console once the domain verifies; the review takes about a day.

## Domain: aegisops.pro

- Route 53 hosted zone for `aegisops.pro`. If the domain is registered elsewhere, set its name servers at the
  registrar to the zone's four NS records.
- ACM certificate for `aegisops.pro`, DNS-validated through the zone; ALB listener 443 with it, 80 redirecting to 443.
- Alias records `aegisops.pro` (A and AAAA) to the ALB.
- In the GitHub App's settings and the Atlassian developer console: callback URLs
  `https://aegisops.pro/admin/integrations/github/callback` and `…/jira/callback`, and the privacy URL
  `https://aegisops.pro/privacy`.

Environment that names it:

| Service | Variables |
|---|---|
| frontend | `NODE_ENV=production`, `AUTH_COOKIE_SECURE=true`, `GATEWAY_URL=http://gateway.<namespace>:8080`, `PRIVACY_OPERATOR`, `PRIVACY_CONTACT` |
| identity-service | `IDENTITY_FRONTEND_BASE_URL=https://aegisops.pro`, `WEBAUTHN_RP_ID=aegisops.pro`, `WEBAUTHN_ORIGIN=https://aegisops.pro`, `CORS_ALLOWED_ORIGINS=https://aegisops.pro`, `IDENTITY_ISSUER=https://aegisops.pro` |
| aegis-core | `AEGIS_GITHUB_CALLBACK_URL`, `AEGIS_JIRA_CALLBACK_URL` (as above), `CORS_ALLOWED_ORIGINS=https://aegisops.pro`, `AEGIS_DEMO_SEED_ENABLED=false` |

## Settings the code fixes need

| Fix | Setting |
|---|---|
| 1. Signing key | identity-service `IDENTITY_JWT_PRIVATE_KEY` from an SSM SecureString holding a PKCS#8 PEM: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`. Once it's set, identity-service may run rolling deploys and more than one task. To rotate: change the parameter and redeploy. Tokens signed with the old key are refused, and the BFF renews them. |
| 2. Scheduler lease | Nothing required. aegis-core may run rolling deploys (`deployment_minimum_healthy_percent = 100`) and several tasks; one runs the scheduled jobs. Optional: `AEGIS_SCHEDULER_LEASE_DURATION` (PT2M), `AEGIS_SCHEDULER_LEASE_RENEWAL` (PT30S). |
| 3. Row-level security | The database roles below. |
| 4. Client IP | Frontend `TRUSTED_PROXY_HOPS=1` (the ALB; 2 with CloudFront in front). Gateway `GATEWAY_TRUSTED_PROXIES`: a Java regex over the frontend's subnets, e.g. `10\.40\.(1\|2)\.\d+` (the default trusts every private address). Security groups: only the frontend reaches the gateway, and only the gateway and aegis-core reach identity-service, because identity-service trusts the first X-Forwarded-For hop it receives. |
| 5. GitHub key | aegis-core `AEGIS_GITHUB_APP_PRIVATE_KEY` from an SSM SecureString holding the App's PKCS#8 PEM (convert GitHub's download: `openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pkcs8.pem`). |

Secrets in SSM Parameter Store as SecureStrings, passed through the task definitions' `secrets`:
`IDENTITY_JWT_PRIVATE_KEY`, `AEGIS_GITHUB_APP_PRIVATE_KEY`, `AEGIS_GITHUB_APP_CLIENT_SECRET`,
`AEGIS_JIRA_CLIENT_SECRET`, `AEGIS_SECRETS_KEY`, `GROQ_API_KEY`, `AI_SERVICE_TOKEN` (aegis-core) = `SERVICE_TOKEN`
(ai-service), `MFA_TOTP_SECRET_KEY`, `DB_PASSWORD` (one per service), the SES SMTP login,
`IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`, `AEGIS_NVD_API_KEY`. Create them in Terraform with write-only values
(`value_wo`) or by hand, so they stay out of state.

## Database roles on RDS (one time, as the master user)

Facts that shape it:
- RDS has no superuser. The master user can't bypass row-level security and can't create a role that does
  ([rds_superuser](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.PostgreSQL.CommonDBATasks.Roles.rds_superuser.html)).
  So aegis-core's policies (V9 onwards, forced on the table owner too) apply to every role, including the one
  running Flyway. The test suite now runs as such a role.
- RDS sits in isolated subnets: neither HCP Terraform's runners nor a laptop can reach it. Run the SQL below from a
  one-off ECS task (`postgres:16` with `psql`) in the app subnets, using the master secret.

```sql
create role aegis_identity login password '…';  -- identity-service: owns the public schema
create role aegis_core login password '…';      -- aegis-core: owns the core schema Flyway creates
create database aegis owner aegis_identity;
grant create on database aegis to aegis_core;
```

Then `DB_URL=jdbc:postgresql://<endpoint>:5432/aegis` and each service's own `DB_USER`/`DB_PASSWORD`. Don't run the
services as the master user. Don't point them at an RDS-managed master secret either: it rotates every 7 days, and
running tasks keep the old password.

**Before the first migration that backfills org-scoped rows** (as V28 and V29 did): on RDS it runs under row-level
security with no organization set, so it silently touches no rows. Either set `aegis.current_org` for each
organization inside the migration, or move aegis-core to an owner/app role split first. In the split, Flyway runs as
the owner (`spring.flyway.user`), the policies are no longer forced, so the owner is exempt, and the service connects
as a second role with only data grants. A fresh database is unaffected: every existing migration then runs on empty
tables.

## Not Terraform, still open

- The four backend images run as root (no `USER` in their Dockerfiles); the frontend already runs as `nextjs`.
  Add a non-root user before production.
- No Zipkin in AWS: set `TRACING_SAMPLE_RATE=0` on the three Java services, or their exporters log failures.
- `IDENTITY_BOOTSTRAP_ADMIN_EMAIL` / `IDENTITY_BOOTSTRAP_ADMIN_PASSWORD` seed the first platform admin; remove them
  once that admin exists.
