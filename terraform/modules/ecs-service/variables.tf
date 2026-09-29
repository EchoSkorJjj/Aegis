variable "name" {
  description = "e.g. aegis-dev-gateway: the service, its task family and its log group."
  type        = string
}

variable "cluster_arn" {
  description = "The cluster."
  type        = string
}

variable "execution_role_arn" {
  description = "The shared task execution role."
  type        = string
}

variable "namespace_id" {
  description = "The Cloud Map namespace the service registers in."
  type        = string
}

variable "discovery_name" {
  description = "The name it registers under, e.g. gateway for gateway.<namespace>."
  type        = string
}

variable "image" {
  description = "<repository url>:<tag>, built for linux/amd64."
  type        = string
}

variable "container_port" {
  description = "The port the container listens on."
  type        = number
}

variable "cpu" {
  description = "Task CPU units (256, 512, 1024, …)."
  type        = number
}

variable "memory" {
  description = "Task memory in MiB; a valid Fargate pair with cpu."
  type        = number
}

variable "environment" {
  description = "Plain environment variables."
  type        = map(string)
}

variable "secrets" {
  description = "Environment variable => SSM parameter ARN, read when a task starts."
  type        = map(string)
}

variable "subnet_ids" {
  description = "The private subnets."
  type        = list(string)
}

variable "security_group_ids" {
  description = "The service's security group."
  type        = list(string)
}

variable "capacity_provider" {
  description = "FARGATE or FARGATE_SPOT."
  type        = string

  validation {
    condition     = contains(["FARGATE", "FARGATE_SPOT"], var.capacity_provider)
    error_message = "capacity_provider must be FARGATE or FARGATE_SPOT."
  }
}

variable "desired_count" {
  description = "Tasks to run; 0 stops the service."
  type        = number
}

variable "load_balanced" {
  description = "Registers the tasks with target_group_arn. A plain flag, known at plan, unlike the target group's ARN."
  type        = bool
  default     = false
}

variable "target_group_arn" {
  description = "The ALB target group, when load_balanced."
  type        = string
  default     = null

  validation {
    condition     = !var.load_balanced || var.target_group_arn != null
    error_message = "A load_balanced service needs target_group_arn."
  }
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention."
  type        = number
}
