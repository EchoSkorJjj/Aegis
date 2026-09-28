# alb

The internet-facing Application Load Balancer: an HTTPS listener with an ACM certificate, HTTP redirected to HTTPS,
the frontend's target group, and the DNS record for the environment's host name.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | Name prefix, e.g. `aegis-dev` (ALB and target group names are at most 32 characters) |
| `vpc_id` | `string` | — | The VPC |
| `subnet_ids` | `list(string)` | — | The public subnets |
| `security_group_ids` | `list(string)` | — | The ALB's security group |
| `zone_id` | `string` | — | The Route 53 zone for the certificate's validation and the alias record |
| `domain_name` | `string` | — | The host name served (`dev.aegisops.pro`, `aegisops.pro`) |
| `idle_timeout` | `number` | — | Seconds; longer than the slowest request (300) |
| `target_port` | `number` | — | The frontend's port (3000) |
| `health_check_path` | `string` | — | The frontend's health check path |
| `health_check_matcher` | `string` | `"200-399"` | Healthy status codes: a redirect to the sign-in page counts (optional) |

## Outputs

| Name | Type | Description |
|---|---|---|
| `alb_dns_name` | `string` | The ALB's own DNS name |
| `target_group_arn` | `string` | The frontend's target group. **Declare it with `depends_on = [aws_lb_listener.https]`:** ECS refuses a target group no listener uses, and the frontend's service is created from this output |

## Notes

- Target group `target_type = "ip"` (Fargate tasks), protocol HTTP.
- Certificate: `aws_acm_certificate` (DNS validation) + its `aws_route53_record`s + `aws_acm_certificate_validation`;
  the HTTPS listener takes `aws_acm_certificate_validation.this.certificate_arn`, so it waits for the certificate.
- HTTPS listener: `ssl_policy = "ELBSecurityPolicy-TLS13-1-2-2021-06"`, forwarding to the target group. HTTP
  listener: redirect to 443 with status 301.
- `aws_route53_record` alias (A) for `domain_name` to the ALB.
