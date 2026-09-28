# Shared by every environment, applied once before them. The module calls are here; resources that need no module
# are split by concern: dns.tf, ecr.tf, ci.tf and budget.tf.

# Sending mail as the domain: SES identity, DKIM, MAIL FROM and DMARC records in the zone. The SMTP login
# identity-service sends with is made by hand in the SES console and kept per environment in SSM.
module "ses_domain" {
  source = "../modules/ses-domain"

  domain_name         = var.domain_name
  zone_id             = aws_route53_zone.primary.zone_id
  mail_from_subdomain = var.mail_from_subdomain
}
