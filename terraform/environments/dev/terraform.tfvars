# Non-secret values for dev (committed). Secrets never go in a tfvars file: they are SSM parameters
# (the secret_parameter_names output lists them).
environment = "dev"
aws_region  = "ap-southeast-1"
root_domain = "aegisops.pro"
domain_name = "dev.aegisops.pro"
vpc_cidr    = "10.40.0.0/16"
capacity    = "spot"

# 0 until the secrets are filled in and the database roles exist; then 1. Back to 0 between demos.
desired_count = 0
image_tag     = "initial" # the tag CI pushed

deletion_protection  = false
registration_enabled = true

# github_app_enabled   = true
# github_app_client_id = "<the App's client ID>"
# jira_enabled         = true
# jira_client_id       = "<the Atlassian app's client ID>"
# nvd_api_key_enabled  = true
# llm_provider         = "groq"
# bootstrap_admin_email = "<the first platform admin>"
# privacy_operator     = "<who runs this deployment>"
# privacy_contact      = "<email or https link>"
