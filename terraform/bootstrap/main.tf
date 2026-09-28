# Lets HCP Terraform reach AWS without stored keys (dynamic provider credentials):
# - the IAM OIDC provider for app.terraform.io
# - the role remote runs assume, trusted only for this organization's aegis-global, aegis-dev and aegis-prod
#   workspaces
# - optionally, those workspaces themselves (tfe_workspace: working directory, execution mode) and their
#   TFC_AWS_PROVIDER_AUTH / TFC_AWS_RUN_ROLE_ARN environment variables (tfe_variable)
