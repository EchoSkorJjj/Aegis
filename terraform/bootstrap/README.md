# bootstrap

Run once, before anything else, and again only to change who may deploy. It creates what lets HCP Terraform
reach AWS without stored keys: an IAM OIDC provider for `app.terraform.io` and the role the `aegis-global`,
`aegis-dev` and `aegis-prod` workspaces' runs assume. It can also create those workspaces and set their
`TFC_AWS_PROVIDER_AUTH=true` and `TFC_AWS_RUN_ROLE_ARN` variables.

It can't use that role itself, so it runs locally with your own AWS credentials: create the `aegis-bootstrap`
workspace by hand in HCP Terraform with **execution mode Local** (its state is still kept there).

```bash
cd terraform/bootstrap
cp .env.example .env    # once; fill in
source .env
terraform init
terraform apply
```
