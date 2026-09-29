# environments

`dev/` and `prod/` are the same root module: every `.tf` file is identical, only `terraform.tfvars` differs. Change
both together (edit one, copy it over, `diff` them).

|               | dev                          | prod                                      |
| ------------- | ---------------------------- | ----------------------------------------- |
| Serves at     | `https://dev.aegisops.pro` | `https://aegisops.pro`                  |
| Workspace     | `aegis-dev`                | `aegis-prod`                            |
| Secrets under | `/aegis/dev/`              | `/aegis/prod/`                          |
| Capacity      | Fargate Spot                 | Fargate on-demand                         |
| Database      | deletable, no final snapshot | deletion protection, final snapshot       |

## What you do by hand, and why

| Step                                     | Why it isn't Terraform                                                                   |
| ---------------------------------------- | ---------------------------------------------------------------------------------------- |
| **KMS: nothing**                   | Everything uses AWS-managed keys. See[KMS](#kms)                                          |
| Create the secrets in SSM                | Terraform keeps whatever it manages in its state, secrets included                       |
| The SES SMTP login                       | Made in the SES console; only you should ever see its password                           |
| Create the database roles (one ECS task) | Nothing outside the VPC can reach the database, HCP Terraform's runners included         |
| Build and push the images                | They come from the code, not the infrastructure (a CI workflow can take this over later) |

## First deploy

Below is dev. For prod, change `dev` to `prod` in paths and names, and see [Prod](#prod).

### 0. Before you start

- `global` is applied, `dig NS aegisops.pro +short` shows the four `awsdns` servers, and SES shows the domain as
  verified (all in [`../global/README.md`](../global/README.md)).
- The AWS CLI is signed in, and every shell below starts with:

  ```bash
  export AWS_PROFILE=aegis-runner AWS_REGION=ap-southeast-1
  ```
- Docker Desktop is running (step 4 only).

### 1. Apply the platform, services stopped

`terraform.tfvars` starts with `desired_count = 0`: this apply builds everything, but no service starts yet.

The first time only, copy `.env.example` to `.env` in `terraform/environments/dev` and set
`TF_CLOUD_ORGANIZATION=echoskorjjj-org` and `TF_WORKSPACE=aegis-dev` in it. Then:

```bash
cd terraform/environments/dev
source .env
terraform init
terraform apply
```

It takes 15–20 minutes, most of it RDS. Commit the `.terraform.lock.hcl` that `init` writes.

It creates the VPC, the NAT instance, the database, the load balancer with its certificate and DNS record, the
cluster, the five services at zero tasks and the database setup task.

### 2. Create the secrets

Every parameter is a SecureString under the AWS-managed `aws/ssm` key. `terraform output secret_parameter_names`
lists the ones this environment's settings need. The first seven are always needed.

This block creates five of them, generating every value:

- `identity/db-password` and `core/db-password`: the passwords the services' own database roles get (step 3
  sets them).
- `ai-service-token`: the token aegis-core presents to ai-service. Both read this one parameter.
- `identity/mfa-totp-secret-key`: the passphrase identity-service encrypts users' TOTP secrets with. **Never
  change it once users enrol TOTP**, or their authenticator apps stop working.
- `identity/jwt-private-key`: the key identity-service signs tokens with, a PKCS#8 PEM.

```bash
P=/aegis/dev

aws ssm put-parameter --type SecureString --name $P/identity/db-password --value "$(openssl rand -hex 32)"
aws ssm put-parameter --type SecureString --name $P/core/db-password --value "$(openssl rand -hex 32)"
aws ssm put-parameter --type SecureString --name $P/ai-service-token --value "$(openssl rand -hex 32)"
aws ssm put-parameter --type SecureString --name $P/identity/mfa-totp-secret-key --value "$(openssl rand -base64 32)"

KEYDIR=$(mktemp -d)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEYDIR/jwt.pem"
aws ssm put-parameter --type SecureString --name $P/identity/jwt-private-key --value "file://$KEYDIR/jwt.pem"
rm -r "$KEYDIR"
```

**The SES SMTP login.** In the SES console, switched to **Asia Pacific (Singapore)** (an SMTP password only works in
the region it was made in): **SMTP settings → IAM SMTP credentials → Create IAM credentials → Create user**. It shows
the user name (it starts with `AKIA`) and the password once; download the `.csv`. One login can serve both
environments. Not **Mail Manager SMTP → Create SMTP credentials** on the same page: that creates a Mail Manager
ingress endpoint (a user name starting `inp-`), which identity-service can't send through, at $50 a month each after
the first. Then:

```bash
P=/aegis/dev
printf 'SMTP user name: '; read -r SMTP_USERNAME
printf 'SMTP password: '; read -rs SMTP_PASSWORD; echo
aws ssm put-parameter --type SecureString --name $P/identity/smtp-username --value "$SMTP_USERNAME"
aws ssm put-parameter --type SecureString --name $P/identity/smtp-password --value "$SMTP_PASSWORD"
unset SMTP_USERNAME SMTP_PASSWORD
```

While SES is still in the sandbox it delivers only to verified addresses: verify the ones you'll sign in with
(SES console → **Identities → Create identity → Email address**), or sign-in codes never arrive.

**Then one parameter per secret of what you turn on** in `terraform.tfvars`, and only those. Of your local
`.env`, only the secret values come here; the switches and client IDs (`LLM_PROVIDER`, the `*_ENABLED` flags,
`*_CLIENT_ID`) are `terraform.tfvars` lines. Paste one block at a time; each asks for its value, which then stays
out of your shell history.

**GitHub App** (`github_app_enabled = true`): its client secret, and its private key. The key must be PKCS#8,
whose first line reads `-----BEGIN PRIVATE KEY-----`; if the App already runs locally, that's the file your
`docker-compose.override.yml` mounts. GitHub's own download (`-----BEGIN RSA PRIVATE KEY-----`) needs converting
first: `openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pkcs8.pem`. Set `KEY_FILE` to the key's full path
(`file://` followed by an absolute path makes three slashes).

```bash
P=/aegis/dev
KEY_FILE=/path/to/app.pkcs8.pem
printf 'GitHub App client secret: '; read -rs VALUE; echo
aws ssm put-parameter --type SecureString --name $P/core/github-app-client-secret --value "$VALUE"
aws ssm put-parameter --type SecureString --name $P/core/github-app-private-key --value "file://$KEY_FILE"
```

**Jira** (`jira_enabled = true`): the Atlassian app's secret, and a new 32-byte key that seals the Jira tokens this
environment stores. **Never change that key once a site is connected**, or the connection has to be made again.

```bash
P=/aegis/dev
printf 'Jira client secret: '; read -rs VALUE; echo
aws ssm put-parameter --type SecureString --name $P/core/jira-client-secret --value "$VALUE"
aws ssm put-parameter --type SecureString --name $P/core/secrets-key --value "$(openssl rand -base64 32)"
```

**Groq** (`llm_provider = "groq"`): your Groq API key.

```bash
P=/aegis/dev
printf 'Groq API key: '; read -rs VALUE; echo
aws ssm put-parameter --type SecureString --name $P/ai/groq-api-key --value "$VALUE"
```

**NVD** (`nvd_api_key_enabled = true`, optional): your NVD API key. Without one, NVD allows 5 requests per
30 seconds; with one, 50.

```bash
P=/aegis/dev
printf 'NVD API key: '; read -rs VALUE; echo
aws ssm put-parameter --type SecureString --name $P/core/nvd-api-key --value "$VALUE"
```

**A platform admin** (optional: only if `bootstrap_admin_email` is set): that account's password. identity-service
creates the account at startup. It belongs to no organization and can disable, delete and promote users across all
of them. Anyone can sign up and create an organization without it, so use an address you won't also sign up with.
While SES is in the sandbox, verify that address too.

```bash
P=/aegis/dev
printf 'First admin password: '; read -rs VALUE; echo
aws ssm put-parameter --type SecureString --name $P/identity/bootstrap-admin-password --value "$VALUE"
```

Each is done once per environment: a parameter stays until you delete it, through every apply and deploy.
`put-parameter` refuses to replace one that exists; to change a value, add `--overwrite`, then redeploy the service
that reads it ([Later](#later)).

Check they're all there (names only):

```bash
aws ssm get-parameters-by-path --path /aegis/dev --recursive --query 'Parameters[].Name'
```

A missing parameter doesn't break an apply: it stops the task that reads it, at start, with
`ResourceInitializationError … ParameterNotFound` in the service's events.

### 3. Create the database roles

One ECS task, as the RDS master user: it creates the `aegis` database, and the roles `aegis_identity` and
`aegis_core` with the two passwords from step 2 ([`dev/database-setup.tf`](dev/database-setup.tf)). From
`terraform/environments/dev`:

```bash
TASK=$(eval "$(terraform output -raw database_setup_command)")
aws ecs wait tasks-stopped --cluster aegis-dev --tasks "$TASK"
aws ecs describe-tasks --cluster aegis-dev --tasks "$TASK" --query 'tasks[0].[containers[0].exitCode, stoppedReason]'
aws logs tail /ecs/aegis-dev-database-setup --since 30m
```

Done when the exit code is `0` and the log ends with `Database setup done`. It's safe to run again: it only sets
the two passwords again.

### 4. Build and push the images

Steps 3 and 4 don't depend on each other: do them in either order, as long as both come before step 5.

ECR tags are immutable, so every push needs a new tag: the commit. Commit first. From the repository root:

```bash
REGISTRY=$(aws sts get-caller-identity --query Account --output text).dkr.ecr.ap-southeast-1.amazonaws.com
TAG=$(git rev-parse --short=12 HEAD)
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
for service in identity-service gateway aegis-core ai-service; do
  docker buildx build --platform linux/amd64 --provenance=false -t "$REGISTRY/aegis/$service:$TAG" --push "backend/$service"
done
docker buildx build --platform linux/amd64 --provenance=false -t "$REGISTRY/aegis/frontend:$TAG" --push frontend
echo "$TAG"
```

`linux/amd64` because Fargate Spot runs no ARM tasks. On an Apple silicon Mac that build is emulated, so the Maven
builds take several minutes each. The images are shared: prod runs the same tags.

### 5. Start the services

In `terraform.tfvars`, set `desired_count = 1` and `image_tag` to the tag from step 4, then `terraform apply`.
The Java services take a minute or two to start, and each runs its migrations the first time.

### 6. Check it

```bash
aws ecs describe-services --cluster aegis-dev --services aegis-dev-frontend aegis-dev-gateway aegis-dev-identity-service aegis-dev-aegis-core aegis-dev-ai-service --query 'services[].[serviceName, runningCount, desiredCount]' --output table
aws logs tail /ecs/aegis-dev-identity-service --since 15m --follow
```

Then open `https://dev.aegisops.pro`: sign up, confirm the email code (step 2's sandbox note), create an organization.

## Later

| To                                | Do                                                                                                                                                                                                                                                                                                           |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Deploy a new version              | Step 4 with the new commit, then`image_tag` in `terraform.tfvars` and `terraform apply`                                                                                                                                                                                                                |
| Change a secret                   | `aws ssm put-parameter --overwrite …`, then redeploy the service that reads it: `aws ecs update-service --cluster aegis-dev --service aegis-dev-<service> --force-new-deployment`                                                                                                                       |
| Rotate the database passwords     | Overwrite both, run step 3 again, then redeploy identity-service and aegis-core straight away (new connections fail in between)                                                                                                                                                                              |
| Rotate the signing key            | Overwrite`identity/jwt-private-key`, redeploy identity-service; signed-in users get new tokens through the BFF                                                                                                                                                                                             |
| Turn on GitHub, Jira, NVD or Groq | Its parameters (step 2's blocks), its switch in`terraform.tfvars`, `terraform apply`. For GitHub and Jira, also add `https://dev.aegisops.pro/admin/integrations/github/callback` (or `…/jira/callback`) to the app's callback URLs, and `https://dev.aegisops.pro/privacy` as Jira's privacy URL |
| Pause between demos               | `desired_count = 0` and apply. The database, load balancer and NAT instance keep running, and billing. Stop the database too with `aws rds stop-db-instance --db-instance-identifier aegis-dev` (AWS starts it again after 7 days)                                                                       |
| Remove dev                        | `terraform destroy`. The parameters stay, so a rebuild reuses them ([below](#rebuilding-after-terraform-destroy)); delete them with `aws ssm delete-parameters` only if dev is gone for good                                                                                                              |

### Rebuilding after `terraform destroy`

A destroy removes only what this environment's state holds. Most of the first deploy lives outside it:

- **Survives:** the secrets in SSM (step 2), the images in ECR (step 4), the SES SMTP login, the domain's zone and
  the apps' callback URLs.
- **Gone for good:** everything in the database (accounts, organizations, services, their SBOMs, the GitHub and
  Jira connections, the ingested advisories) and the logs. Dev keeps no final snapshot.

So a rebuild repeats only the database roles and the start:

1. Set `desired_count = 0` and apply. With 1, identity-service and aegis-core would start before their database
   roles exist and keep failing.
2. Create the database roles again ([step 3](#3-create-the-database-roles)). The new database is empty, and its
   roles get the same passwords from the parameters.
3. Set `desired_count = 1` and apply. Then sign up and set the app up again.

Already applied with 1? Create the roles, then redeploy identity-service and aegis-core with
`aws ecs update-service --cluster aegis-dev --service <name> --force-new-deployment`.

To stop paying between demos without losing anything, pause instead (the table above).

## Prod

The same steps with `aegis-prod` / `/aegis/prod`, plus:

- **SES production access** must be granted first, or only verified addresses receive mail.
- **The apps' callback URLs.** With GitHub or Jira on, add `https://aegisops.pro/admin/integrations/github/callback`
  to the GitHub App and `https://aegisops.pro/admin/integrations/jira/callback` to the Atlassian app, next to the
  dev and localhost ones: each environment sends its own as `redirect_uri`, and both sites accept only an exact
  match.
- **Deletion protection.** `terraform destroy` fails on the database until you set `deletion_protection = false`
  and apply; the final snapshot `aegis-prod-final` is kept either way.

## KMS

There is nothing to create. Every service Aegis uses encrypts under an **AWS-managed key**: free, made by AWS the
first time it's needed, and usable by any role in the account through the service that owns it, so no key policy
and no `kms:Decrypt` grant.

| What                                      | Encrypted with                              |
| ----------------------------------------- | ------------------------------------------- |
| SSM parameters (the services' secrets)    | `aws/ssm`                                 |
| RDS storage, backups and snapshots        | `aws/rds`                                 |
| The RDS master password (Secrets Manager) | `aws/secretsmanager`                      |
| ECR images, CloudWatch logs               | Encrypted by the service itself, no KMS key |

A customer-managed key costs $1 a month each plus its API calls, and needs a key policy you keep correct. It only
pays off to share encrypted data with another account, to limit decryption beyond IAM, or when an auditor asks
for your own key. If prod ever needs one, decide before it holds data: parameters can simply be put again with
`--key-id`, but moving RDS to another key means copying a snapshot and restoring from it.
