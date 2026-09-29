# bootstrap

Run once, before anything else, and again only to change who may deploy. It creates what lets HCP Terraform
reach AWS without stored keys, and the workspaces that use it:

- an IAM OIDC provider for `app.terraform.io`, and the role `aegis-hcp-terraform` that only runs of the
  `aegis-global`, `aegis-dev` and `aegis-prod` workspaces may assume
- the HCP Terraform project `aegis` with those three workspaces, each with its Terraform Working Directory
- for bootstrap to run, you need to have created your account on [app.terraform.io/app](https://app.terraform.io/app/), create the default
  project, and create workspace named "aegis-bootstrap"
- a variable set on the project: `TFC_AWS_PROVIDER_AUTH=true` and `TFC_AWS_RUN_ROLE_ARN`

It can't use that role itself, so it runs locally with your own AWS sign-in: create the `aegis-bootstrap`
workspace by hand in HCP Terraform with **execution mode Local** (its state is still kept there).

`terraform login` stores your HCP Terraform token (the tfe provider reads it too); run it once. `aws login` turns
your IAM user's console sign-in into short-lived CLI credentials. The first time, also copy `.env.example` to `.env`
and fill it in.

```bash
terraform login
```

```bash
aws login --profile aegis-runner
cd terraform/bootstrap
source .env
terraform init
terraform apply
```

If Terraform's AWS provider doesn't read the `aws login` profile, export its temporary credentials first
(again when they expire): `eval "$(aws configure export-credentials --profile aegis-runner --format env)"`.

If you had already created any of these workspaces, or the `aegis` project, by hand, the apply fails on
the name: import it first, e.g. `terraform import 'tfe_workspace.this["aegis-dev"]' <org>/aegis-dev`.
