# bootstrap

Run once, before anything else, and again only to change who may deploy. It creates what lets HCP Terraform
reach AWS without stored keys, and the workspaces that use it:

- an IAM OIDC provider for `app.terraform.io`, and the role `aegis-hcp-terraform` that only runs of the
  `aegis-global`, `aegis-dev` and `aegis-prod` workspaces may assume
- the HCP Terraform project `aegis` with those three workspaces, each with its Terraform Working Directory
- a variable set on the project: `TFC_AWS_PROVIDER_AUTH=true` and `TFC_AWS_RUN_ROLE_ARN`

It can't use that role itself, so it runs locally with your own AWS sign-in: create the `aegis-bootstrap`
workspace by hand in HCP Terraform with **execution mode Local** (its state is still kept there).

```bash
terraform login                       # once: HCP Terraform token (the tfe provider reads it too)
aws login --profile aegis-runner      # your IAM user's console sign-in, as short-lived CLI credentials
cd terraform/bootstrap
cp .env.example .env                  # once; fill in
source .env
terraform init
terraform apply
```

If Terraform's AWS provider doesn't read the `aws login` profile, export its temporary credentials first
(again when they expire): `eval "$(aws configure export-credentials --profile aegis-runner --format env)"`.

Afterwards:

- Point `environments/dev/.env` at `aegis-dev` (it names the old test workspace), and delete `aegis-test`.
- If you had already created any of these workspaces, or the `aegis` project, by hand, the apply fails on
  the name: import it first, e.g. `terraform import 'tfe_workspace.this["aegis-dev"]' <org>/aegis-dev`.
