variable "aws_region" {
  description = "Region of the provider. The resources here (IAM) are global."
  type        = string
}

variable "hcp_hostname" {
  description = "HCP Terraform's host: the issuer of the identity tokens AWS trusts."
  type        = string
  default     = "app.terraform.io"
}

variable "hcp_organization" {
  description = "The HCP Terraform organization holding the workspaces."
  type        = string
}

variable "hcp_project" {
  description = "The HCP Terraform project created for the workspaces below."
  type        = string
  default     = "aegis"
}

variable "workspaces" {
  description = "The workspaces created here, whose runs may assume the AWS role: name => Terraform Working Directory, relative to terraform/."
  type        = map(string)
  default = {
    "aegis-global" = "global"
    "aegis-dev"    = "environments/dev"
    "aegis-prod"   = "environments/prod"
  }
}

variable "role_name" {
  description = "Name of the IAM role HCP Terraform's runs assume."
  type        = string
  default     = "aegis-hcp-terraform"
}
