variable "aws_region" {
  description = "Region for what's regional here (ECR, SES). Use the environments' region: SES sends from where its identity is."
  type        = string
}

variable "domain_name" {
  description = "The domain Aegis serves under: its Route 53 zone and SES identity live here."
  type        = string
}

variable "services" {
  description = "The services that get an ECR repository, named aegis/<service>."
  type        = set(string)
  default     = ["frontend", "gateway", "identity-service", "aegis-core", "ai-service"]
}

variable "ecr_images_to_keep" {
  description = "How many images each repository keeps; older ones expire."
  type        = number
  default     = 15
}

variable "github_repository" {
  description = "owner/name of the repository whose GitHub Actions workflows may push images."
  type        = string
}

variable "github_branches" {
  description = "Branches whose workflows may push images."
  type        = list(string)
  default     = ["main", "develop"]
}

variable "mail_from_subdomain" {
  description = "Subdomain SES uses as the MAIL FROM domain (bounces, SPF)."
  type        = string
  default     = "mail"
}

variable "monthly_budget_usd" {
  description = "Monthly AWS spend the budget alerts are measured against."
  type        = number
}

variable "budget_alert_emails" {
  description = "Who gets the budget alerts."
  type        = list(string)
  default     = []
}
