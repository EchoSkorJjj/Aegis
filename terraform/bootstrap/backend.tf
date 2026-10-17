terraform {
  # State lives in HCP Terraform. A cloud block can't use variables, locals or any expression, so it stays
  # empty and Terraform reads its settings from the environment (copy .env.example to .env, fill it in, and
  # `source .env` before running terraform):
  #   TF_CLOUD_ORGANIZATION  the HCP Terraform organization
  #   TF_WORKSPACE           this root's workspace; it must already exist in that organization
  #   TF_CLOUD_PROJECT       optional: the project holding the workspace
  #   TF_CLOUD_HOSTNAME      optional: a Terraform Enterprise host (defaults to app.terraform.io)
  cloud {}
}
