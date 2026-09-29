# network

A VPC across two availability zones with three subnet tiers: public (the ALB, the NAT instance), private (the ECS
tasks, no public IPs) and isolated (RDS, no route out). An internet gateway, route tables, a NAT instance for the
private subnets' outbound traffic, and the S3 gateway endpoint so ECR image layers skip the NAT.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | Name prefix, e.g. `aegis-dev` |
| `vpc_cidr` | `string` | — | The VPC's range, a /16 |
| `availability_zones` | `list(string)` | — | Exactly two availability zones, one subnet of each tier in each |
| `nat_instance_type` | `string` | `"t4g.nano"` | The NAT instance's size, an arm64 type (optional: the roots don't pass it) |

## Outputs

| Name | Type | Description |
|---|---|---|
| `vpc_id` | `string` | The VPC |
| `public_subnet_ids` | `list(string)` | For the ALB, in `availability_zones` order |
| `private_subnet_ids` | `list(string)` | For the ECS tasks |
| `isolated_subnet_ids` | `list(string)` | For RDS |
| `nat_instance_id` | `string` | The NAT instance |

## Notes

- Subnets are /20s, `cidrsubnet(vpc_cidr, 4, n)`: public n = 0, 1; private 2, 3; isolated 4, 5. For
  `10.40.0.0/16`: public `10.40.0.0/20` and `10.40.16.0/20`, private `10.40.32.0/20` and `10.40.48.0/20`, isolated
  `10.40.64.0/20` and `10.40.80.0/20`.
- The NAT instance is plain Amazon Linux 2023 (arm64, the AMI AWS publishes in SSM) configured by its user data:
  IP forwarding plus an iptables `MASQUERADE` rule, `source_dest_check = false`. About $3 a month against a NAT
  Gateway's $33 before data. It's one instance in the first zone: if it stops, the tasks lose the internet (the
  feeds, GitHub, Jira, Groq, SES) but not each other or the database. EC2 recovers it on a host failure. A newer
  AMI doesn't replace it on apply (`ignore_changes = [ami]`); to pick one up:
  `terraform apply -replace=module.network.aws_instance.nat`.
- The private route table sends `0.0.0.0/0` to the NAT instance's network interface; the isolated one has no
  default route. The S3 gateway endpoint sits on the private route table (ECR stores image layers in S3).
