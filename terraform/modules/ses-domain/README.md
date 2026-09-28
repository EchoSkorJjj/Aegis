# ses-domain

SES for aegisops.pro: the domain identity with Easy DKIM and its three DNS records, a custom MAIL FROM domain
(MX + SPF), a DMARC record, and the IAM user allowed only ses:SendRawEmail whose SMTP login identity-service
sends with, kept in SSM. Production access (leaving the SES sandbox) is requested by hand.

Used by: global (global/main.tf).

| | |
|---|---|
| Inputs | domain, zone id, MAIL FROM subdomain, SSM parameter prefix, tags |
| Outputs | identity ARN, SMTP endpoint, the SSM parameter names holding the SMTP username and password |
