# The SecureString parameters the services read their secrets from, by name. A person creates each one (see
# environments/README.md); Terraform only works out their names and ARNs, for the task definitions and the execution
# role. It never manages them: an aws_ssm_parameter reads its value back into state on every refresh, even one
# Terraform didn't set. A parameter that doesn't exist yet stops only the tasks that read it, when they start.

data "aws_partition" "current" {}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

locals {
  paths = { for name in var.names : name => "${var.prefix}/${name}" }
}
