variable "environment" {
  description = "Names every resource: aegis-<environment>-<component>."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be dev or prod."
  }
}

variable "aws_region" {
  description = "Where this environment runs; global's region too (SES sends from there)."
  type        = string
}

variable "root_domain" {
  description = "The domain whose Route 53 zone global created."
  type        = string
}

variable "domain_name" {
  description = "Where this environment serves: the root domain, or a subdomain of it."
  type        = string
}

variable "vpc_cidr" {
  description = "The VPC's range, a /16 (the gateway's trusted-proxy pattern assumes one)."
  type        = string

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && endswith(var.vpc_cidr, "/16")
    error_message = "vpc_cidr must be a /16, e.g. 10.40.0.0/16."
  }
}

variable "capacity" {
  description = "spot: Fargate Spot (about 70% cheaper; a task can be stopped with two minutes' notice). on_demand: regular Fargate."
  type        = string
  default     = "spot"

  validation {
    condition     = contains(["spot", "on_demand"], var.capacity)
    error_message = "capacity must be spot or on_demand."
  }
}

variable "desired_count" {
  description = "Tasks per service. 0 stops the services (between demos, or until the secrets and database roles exist); the database, load balancer and NAT instance keep running."
  type        = number
  default     = 1
}

variable "image_tag" {
  description = "The image tag every service runs: the one CI pushed (the commit)."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Initial database storage, GB."
  type        = number
  default     = 20
}

variable "db_max_allocated_storage" {
  description = "Storage autoscaling ceiling, GB."
  type        = number
  default     = 100
}

variable "db_backup_retention_days" {
  description = "Days of automated backups."
  type        = number
  default     = 7
}

variable "deletion_protection" {
  description = "Protects the database from deletion, and keeps a final snapshot if it's deleted anyway."
  type        = bool
  default     = true
}

variable "alb_idle_timeout" {
  description = "Seconds the load balancer holds an idle connection: longer than the slowest request (a repository SBOM import can take up to 270 s)."
  type        = number
  default     = 300
}

variable "log_retention_days" {
  description = "Days the services' CloudWatch logs are kept."
  type        = number
  default     = 14
}

variable "registration_enabled" {
  description = "Public sign-up. Off: invite-only."
  type        = bool
  default     = false
}

variable "bootstrap_admin_email" {
  description = "Seeds the first platform admin at startup, with the identity/bootstrap-admin-password parameter as password. Empty seeds nobody; clear it once that admin exists."
  type        = string
  default     = ""
}

variable "github_app_enabled" {
  description = "Turns the GitHub App on (upgrade pull requests, private clones). Needs its client ID here and its key and client secret in SSM."
  type        = bool
  default     = false
}

variable "github_app_client_id" {
  description = "The GitHub App's client ID (public)."
  type        = string
  default     = ""
}

variable "jira_enabled" {
  description = "Turns Jira on (filing risk tickets). Needs its client ID here and its client secret and the secrets key in SSM."
  type        = bool
  default     = false
}

variable "jira_client_id" {
  description = "The Atlassian app's client ID (public)."
  type        = string
  default     = ""
}

variable "nvd_api_key_enabled" {
  description = "Reads an NVD API key from SSM, raising NVD's rate limit from 5 to 50 requests per 30 s."
  type        = bool
  default     = false
}

variable "osv_ecosystems" {
  description = "OSV ecosystems synced daily, comma-separated; each is a full snapshot download."
  type        = string
  default     = "npm,PyPI,crates.io,RubyGems,Go,Maven"
}

variable "llm_provider" {
  description = "stub (offline, no key) or groq (needs the ai/groq-api-key parameter)."
  type        = string
  default     = "stub"

  validation {
    condition     = contains(["stub", "groq"], var.llm_provider)
    error_message = "llm_provider must be stub or groq."
  }
}

variable "groq_model" {
  description = "The Groq model the ai-service drafts with."
  type        = string
  default     = "openai/gpt-oss-120b"
}

variable "privacy_operator" {
  description = "Who runs this deployment, shown on /privacy."
  type        = string
  default     = ""
}

variable "privacy_contact" {
  description = "An email address or https link on /privacy."
  type        = string
  default     = ""
}
