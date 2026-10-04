variable "name" {
  description = "Name prefix, e.g. aegis-dev (ALB and target group names are at most 32 characters)."
  type        = string
}

variable "vpc_id" {
  description = "The VPC."
  type        = string
}

variable "subnet_ids" {
  description = "The public subnets."
  type        = list(string)
}

variable "security_group_ids" {
  description = "The ALB's security group."
  type        = list(string)
}

variable "zone_id" {
  description = "The Route 53 zone: the certificate's validation records and the alias go in it."
  type        = string
}

variable "domain_name" {
  description = "The host name served, e.g. dev.aegisops.pro."
  type        = string
}

variable "idle_timeout" {
  description = "Seconds an idle connection is held: longer than the slowest request."
  type        = number
}

variable "target_port" {
  description = "The frontend's port."
  type        = number
}

variable "health_check_path" {
  description = "The frontend's health check path."
  type        = string
}

variable "health_check_matcher" {
  description = "Healthy status codes: a redirect to the sign-in page counts."
  type        = string
  default     = "200-399"
}
