# Lets HCP Terraform reach AWS without stored keys (dynamic provider credentials): each run of the workspaces
# below exchanges an identity token HCP Terraform signs for the role's short-lived credentials.

locals {
  # The audience HCP Terraform puts in its tokens unless TFC_AWS_WORKLOAD_IDENTITY_AUDIENCE says otherwise.
  audience = "aws.workload.identity"
}

data "aws_partition" "current" {}

# 1. AWS trusts HCP Terraform's identity tokens.
resource "aws_iam_openid_connect_provider" "hcp_terraform" {
  url            = "https://${var.hcp_hostname}"
  client_id_list = [local.audience]
}

# 2. The role a run assumes: only runs of these workspaces, in this organization and project.
data "aws_iam_policy_document" "hcp_terraform_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.hcp_terraform.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.hcp_hostname}:aud"
      values   = [local.audience]
    }

    condition {
      test     = "StringLike"
      variable = "${var.hcp_hostname}:sub"
      values = [
        for workspace in keys(var.workspaces) :
        "organization:${var.hcp_organization}:project:${var.hcp_project}:workspace:${workspace}:run_phase:*"
      ]
    }
  }
}

resource "aws_iam_role" "hcp_terraform" {
  name               = var.role_name
  description        = "Assumed by HCP Terraform runs of the Aegis workspaces"
  assume_role_policy = data.aws_iam_policy_document.hcp_terraform_trust.json
}

# Administrator: the stacks create IAM roles (ECS tasks, CI) as well as everything else. To tighten it later,
# split plan and apply: a read-only role in TFC_AWS_PLAN_ROLE_ARN, this one in TFC_AWS_APPLY_ROLE_ARN.
resource "aws_iam_role_policy_attachment" "hcp_terraform_admin" {
  role       = aws_iam_role.hcp_terraform.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AdministratorAccess"
}

# 3. The workspaces, in their own project, each told where its configuration is.
resource "tfe_project" "aegis" {
  name = var.hcp_project
}

resource "tfe_workspace" "this" {
  for_each = var.workspaces

  name              = each.key
  project_id        = tfe_project.aegis.id
  working_directory = each.value
  description       = "Aegis: terraform/${each.value}"
}

# 4. Every workspace in the project authenticates to AWS as the role.
resource "tfe_variable_set" "aws" {
  name        = "aegis-aws"
  description = "Dynamic AWS credentials for the Aegis workspaces (the role bootstrap created)"
}

resource "tfe_project_variable_set" "aws" {
  project_id      = tfe_project.aegis.id
  variable_set_id = tfe_variable_set.aws.id
}

resource "tfe_variable" "provider_auth" {
  variable_set_id = tfe_variable_set.aws.id
  category        = "env"
  key             = "TFC_AWS_PROVIDER_AUTH"
  value           = "true"
}

resource "tfe_variable" "run_role_arn" {
  variable_set_id = tfe_variable_set.aws.id
  category        = "env"
  key             = "TFC_AWS_RUN_ROLE_ARN"
  value           = aws_iam_role.hcp_terraform.arn
}
