variable "name" {
  description = "Name prefix, e.g. aegis-dev."
  type        = string
}

variable "subnet_ids" {
  description = "The isolated subnets."
  type        = list(string)
}

variable "security_group_ids" {
  description = "The database's security group."
  type        = list(string)
}

variable "engine_version" {
  description = "PostgreSQL's major version, e.g. 16 (RDS picks the minor version and upgrades it)."
  type        = string
}

variable "instance_class" {
  description = "e.g. db.t4g.micro."
  type        = string
}

variable "allocated_storage" {
  description = "Initial storage, GB."
  type        = number
}

variable "max_allocated_storage" {
  description = "Storage autoscaling ceiling, GB."
  type        = number
}

variable "master_username" {
  description = "The master user: only for the one-time database setup."
  type        = string
}

variable "backup_retention_period" {
  description = "Days of automated backups."
  type        = number
}

variable "deletion_protection" {
  description = "Refuse deletion."
  type        = bool
}

variable "skip_final_snapshot" {
  description = "No snapshot when the database is deleted."
  type        = bool
}
