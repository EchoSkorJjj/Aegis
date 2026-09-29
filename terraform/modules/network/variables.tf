variable "name" {
  description = "Name prefix, e.g. aegis-dev."
  type        = string
}

variable "vpc_cidr" {
  description = "The VPC's range, a /16."
  type        = string
}

variable "availability_zones" {
  description = "Two availability zones: one subnet of each tier in each."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Give exactly two availability zones."
  }
}

variable "nat_instance_type" {
  description = "The NAT instance's size (an arm64 type: the AMI is Amazon Linux 2023 for arm64)."
  type        = string
  default     = "t4g.nano"
}
