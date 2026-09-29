# Your own credentials: AWS_PROFILE from .env (see README.md), not the role this configuration creates.
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "aegis"
      Environment = "bootstrap"
      ManagedBy   = "terraform"
    }
  }
}

# HCP Terraform itself. It reads the token `terraform login` stored.
provider "tfe" {
  hostname     = var.hcp_hostname
  organization = var.hcp_organization
}
