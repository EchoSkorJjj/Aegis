variable "name" {
  description = "Name prefix, e.g. aegis-dev."
  type        = string
}

variable "vpc_id" {
  description = "The VPC."
  type        = string
}

variable "service_ports" {
  description = "Each service's port: frontend, gateway, identity-service, aegis-core, ai-service."
  type        = map(number)

  validation {
    condition = alltrue([
      for service in ["frontend", "gateway", "identity-service", "aegis-core", "ai-service"] :
      contains(keys(var.service_ports), service)
    ])
    error_message = "service_ports needs frontend, gateway, identity-service, aegis-core and ai-service."
  }
}

variable "database_port" {
  description = "PostgreSQL's port."
  type        = number
}
