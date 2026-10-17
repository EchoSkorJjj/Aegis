variable "prefix" {
  description = "The parameters' path, e.g. /aegis/dev."
  type        = string

  validation {
    condition     = can(regex("^(/[A-Za-z0-9_.-]+)+$", var.prefix))
    error_message = "prefix must start with / and not end with one, e.g. /aegis/dev."
  }
}

variable "names" {
  description = "Parameter names under the prefix, e.g. identity/jwt-private-key."
  type        = set(string)
}
