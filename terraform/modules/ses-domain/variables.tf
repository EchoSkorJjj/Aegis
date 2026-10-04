variable "domain_name" {
  description = "The domain mail is sent as, e.g. aegisops.pro."
  type        = string
}

variable "zone_id" {
  description = "The domain's Route 53 hosted zone, where the verification records go."
  type        = string
}

variable "mail_from_subdomain" {
  description = "The MAIL FROM subdomain (bounces and SPF), e.g. mail for mail.aegisops.pro."
  type        = string
}
