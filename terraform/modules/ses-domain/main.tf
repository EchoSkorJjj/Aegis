# SES for the domain: the sending identity with Easy DKIM, a custom MAIL FROM domain, and a DMARC record, all
# published in the domain's zone. Nothing here waits for SES to verify the domain: that happens on its own once
# the domain's name servers point at the zone, which may be after this apply.

data "aws_region" "current" {}

locals {
  mail_from_domain = "${var.mail_from_subdomain}.${var.domain_name}"
  dkim_tokens      = aws_sesv2_email_identity.this.dkim_signing_attributes[0].tokens
}

resource "aws_sesv2_email_identity" "this" {
  email_identity = var.domain_name

  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

# Easy DKIM: SES signs with three keys, each published as a CNAME to its public half.
resource "aws_route53_record" "dkim" {
  count = 3

  zone_id = var.zone_id
  name    = "${local.dkim_tokens[count.index]}._domainkey.${var.domain_name}"
  type    = "CNAME"
  ttl     = 1800
  records = ["${local.dkim_tokens[count.index]}.dkim.amazonses.com"]
}

# Bounces go to the MAIL FROM subdomain, and SPF passes on it; SES falls back to amazonses.com until it verifies.
resource "aws_sesv2_email_identity_mail_from_attributes" "this" {
  email_identity         = aws_sesv2_email_identity.this.email_identity
  mail_from_domain       = local.mail_from_domain
  behavior_on_mx_failure = "USE_DEFAULT_VALUE"
}

resource "aws_route53_record" "mail_from_mx" {
  zone_id = var.zone_id
  name    = local.mail_from_domain
  type    = "MX"
  ttl     = 1800
  records = ["10 feedback-smtp.${data.aws_region.current.region}.amazonses.com"]
}

resource "aws_route53_record" "mail_from_spf" {
  zone_id = var.zone_id
  name    = local.mail_from_domain
  type    = "TXT"
  ttl     = 1800
  records = ["v=spf1 include:amazonses.com ~all"]
}

# DMARC in monitoring mode: receivers check DKIM and SPF alignment but act on nothing yet. Tighten to
# p=quarantine once mail is known to pass.
resource "aws_route53_record" "dmarc" {
  zone_id = var.zone_id
  name    = "_dmarc.${var.domain_name}"
  type    = "TXT"
  ttl     = 1800
  records = ["v=DMARC1; p=none;"]
}
