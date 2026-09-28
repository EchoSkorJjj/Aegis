# ses-domain

SES for the domain: the sending identity with Easy DKIM, a custom MAIL FROM domain, and a DMARC record, all
published in the domain's zone.

Used by: global (`global/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `domain_name` | `string` | — | e.g. `aegisops.pro` |
| `zone_id` | `string` | — | The domain's Route 53 zone |
| `mail_from_subdomain` | `string` | — | e.g. `mail` → `mail.aegisops.pro` |

## Outputs

| Name | Type | Description |
|---|---|---|
| `identity_arn` | `string` | The SES identity |
| `smtp_endpoint` | `string` | `email-smtp.<region>.amazonaws.com`: identity-service's `SPRING_MAIL_HOST` |

## Notes

- `aws_sesv2_email_identity` for `domain_name`; its `dkim_signing_attributes[0].tokens` become three CNAME records
  (`<token>._domainkey.<domain>` → `<token>.dkim.amazonses.com`).
- `aws_sesv2_email_identity_mail_from_attributes` with an MX record (`feedback-smtp.<region>.amazonses.com`, priority
  10) and an SPF TXT record (`v=spf1 include:amazonses.com ~all`) on the MAIL FROM domain.
- DMARC: a TXT record `_dmarc.<domain>` = `v=DMARC1; p=none;`.
- By hand, outside Terraform: request production access (SES starts in the sandbox), and create the SMTP login in
  the SES console (SMTP settings → Create SMTP credentials), then put it in each environment's
  `identity/smtp-username` and `identity/smtp-password` parameters.
