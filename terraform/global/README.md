# global

What every environment shares: the `aegisops.pro` hosted zone, the ECR repositories, the CI push role, the monthly
budget and SES. Applied remotely in the `aegis-global` workspace, before any environment.

The domain is registered at GoDaddy and stays there: Route 53 only answers its DNS, once GoDaddy points the domain
at the zone. (A transfer to Route 53 is optional, and not possible in the first 60 days after registration anyway.)
SES verifies the domain through records in that zone, so apply SES only once the delegation below is in place.

## 1. The zone, without SES

Keep the SES module call in `main.tf` (`module "ses_domain"`) and its two outputs in `outputs.tf`
(`ses_identity_arn`, `ses_smtp_endpoint`) commented out, then:

```bash
cd terraform/global
source .env
terraform init
terraform apply
```

## 2. Point GoDaddy at the zone

The zone's four name servers:

```bash
terraform output name_servers
```

In GoDaddy: **My Products → aegisops.pro → DNS → Nameservers → Change nameservers → I'll use my own nameservers**.
Enter all four, without the trailing dot, and save. If DNSSEC is on for the domain, turn it off first, or the
domain stops resolving. GoDaddy's own DNS records stop applying once you save; that's expected.

## 3. Wait for the delegation

```bash
dig NS aegisops.pro +short
```

Done when it prints the same four `awsdns` name servers as step 2. That's usually minutes to a few hours, at
most 48. If your network's resolver still answers from its cache, ask a public one: `dig NS aegisops.pro +short @1.1.1.1`.

## 4. SES

Uncomment the module call and the two outputs, then `terraform apply` again. SES then verifies the domain by itself;
check with:

```bash
aws sesv2 get-email-identity --email-identity aegisops.pro --profile aegis-runner --query '{Verified:VerifiedForSendingStatus,Dkim:DkimAttributes.Status,MailFrom:MailFromAttributes.MailFromDomainStatus}'
```

Verified once it reads `true`, `SUCCESS`, `SUCCESS`. Production access and the SMTP login are made by hand; see
[`../modules/ses-domain/README.md`](../modules/ses-domain/README.md).

The environments need step 3 too: their HTTPS certificates are validated through the same zone.
