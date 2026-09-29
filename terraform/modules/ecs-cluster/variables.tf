variable "name" {
  description = "Name prefix, e.g. aegis-dev."
  type        = string
}

variable "vpc_id" {
  description = "The VPC the private namespace answers in."
  type        = string
}

variable "namespace_name" {
  description = "The private DNS namespace the services find each other in, e.g. dev.aegis.internal."
  type        = string
}

variable "secret_arns" {
  description = "The SSM parameters the task execution role may read: the services' secrets."
  type        = list(string)
}
