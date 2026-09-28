# Remote runs get their credentials from bootstrap's role (TFC_AWS_PROVIDER_AUTH on the workspace).
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "aegis"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
