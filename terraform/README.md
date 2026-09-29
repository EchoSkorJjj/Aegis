# Aegis infrastructure

Aegis on AWS, managed with Terraform in HCP Terraform: ECS on Fargate behind one ALB, RDS PostgreSQL 16, a NAT
instance, at **aegisops.pro**. To deploy an environment the first time (secrets, images, the database roles), follow
[`environments/README.md`](environments/README.md); `todo/aws-deployment.md` explains why each setting is what it is.

## Layout

```
terraform/
├── bootstrap/            once, first: lets HCP Terraform reach AWS (OIDC), and its workspaces
├── global/               shared by every environment: the zone, ECR, SES, CI access, the budget
├── environments/
│   ├── dev/              one environment: network, database, cluster, load balancer, the five services
│   └── prod/
└── modules/              building blocks the environments and global compose
    ├── network/          VPC, three subnet tiers × two AZs, NAT instance, S3 endpoint
    ├── security-groups/  who may reach whom: ALB → frontend → gateway → services → database
    ├── alb/              load balancer, HTTPS certificate, the environment's DNS record
    ├── ecs-cluster/      cluster, Fargate capacity providers, service discovery, execution role
    ├── ecs-service/      one service (task definition, service, logs, task role); used five times
    ├── rds-postgres/     PostgreSQL instance, subnet and parameter groups
    ├── ssm-secrets/      the names and ARNs of the SecureString parameters the services read
    └── ses-domain/       SES identity, DKIM, MAIL FROM, DMARC
```

`bootstrap/`, `global/` and each `environments/<name>/` is a **root module**: its own state, its own HCP
Terraform workspace, applied on its own. The **modules** follow Terraform's standard module structure
(`main.tf`, `variables.tf`, `outputs.tf`, `versions.tf`, `README.md`) and never configure a provider or a
backend; the root that calls them does.

| Root | Workspace | Execution | Terraform Working Directory |
|---|---|---|---|
| `bootstrap` | `aegis-bootstrap` | Local (your own AWS credentials) | — |
| `global` | `aegis-global` | Remote | `global` |
| `environments/dev` | `aegis-dev` | Remote | `environments/dev` |
| `environments/prod` | `aegis-prod` | Remote | `environments/prod` |

Apply in that order: bootstrap, then global, then the environments. An environment finds what global made by
name, with data sources (`data.tf`), not through global's state.

## A root module's files

| File | Holds |
|---|---|
| `backend.tf` | the empty `cloud {}` block; the organization and workspace come from `.env` |
| `versions.tf` | the Terraform version and the pinned providers |
| `providers.tf` | the AWS provider: region, default tags |
| `main.tf` | the module calls (an environment's services are in `services.tf`) |
| `variables.tf`, `outputs.tf` | inputs and outputs |
| `terraform.tfvars` | this root's values, non-secret only; committed |
| `.env.example` | copy to `.env` (git-ignored): `TF_CLOUD_ORGANIZATION`, `TF_WORKSPACE` |
| `.terraform.lock.hcl` | written by `terraform init`; committed |

## Running

Once: sign the CLI in to HCP Terraform, which stores a token for it, and in each root copy `.env.example` to
`.env` and fill it in.

```bash
terraform login
```

Then, from the root you're working on:

```bash
cd terraform/environments/dev
source .env
terraform init
terraform plan
```

- **Set each remote workspace's Terraform Working Directory** as in the table above, relative to `terraform/`.
  The environments use `../../modules`, so a remote run needs more than their own folder: with the working
  directory set, the CLI uploads `terraform/` as a whole. [`.terraformignore`](.terraformignore) keeps `.env`
  files and local overrides out of that upload.
- Remote runs get AWS credentials from bootstrap's OIDC role, through the workspace environment variables
  `TFC_AWS_PROVIDER_AUTH=true` and `TFC_AWS_RUN_ROLE_ARN`. No AWS keys are stored anywhere.

## Conventions

- **Names** `aegis-<environment>-<component>` (e.g. `aegis-dev-gateway`); every resource tagged `Project`,
  `Environment` and `ManagedBy` through the provider's default tags.
- **Secrets never go in a tfvars file or in state.** They are SSM parameters a person creates
  ([`environments/README.md`](environments/README.md)); Terraform only knows their names, and the services read
  them through their task definitions. The RDS master password is RDS's own, in Secrets Manager.
- **Formatting**: `terraform fmt -recursive` and `terraform validate` before every commit.
