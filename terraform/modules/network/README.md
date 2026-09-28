# network

A VPC across two availability zones with three subnet tiers: public (the ALB, the NAT instance), private (the ECS
tasks, no public IPs) and isolated (RDS, no route out). An internet gateway, route tables, a NAT instance (fck-nat
on a t4g.nano) for the private subnets' outbound traffic, and the S3 gateway endpoint so ECR image layers skip the
NAT.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | Name prefix, e.g. `aegis-dev` |
| `vpc_cidr` | `string` | — | The VPC's range, a /16 |
| `availability_zones` | `list(string)` | — | Two availability zones, one subnet of each tier in each |
| `nat_instance_type` | `string` | `"t4g.nano"` | The NAT instance's size (optional: the roots don't pass it) |

## Outputs

| Name | Type | Description |
|---|---|---|
| `vpc_id` | `string` | The VPC |
| `public_subnet_ids` | `list(string)` | For the ALB |
| `private_subnet_ids` | `list(string)` | For the ECS tasks |
| `isolated_subnet_ids` | `list(string)` | For RDS |

## Notes

- Carve the subnets with `cidrsubnet(var.vpc_cidr, 4, n)` (/20s), one per tier per zone.
- The private route tables send `0.0.0.0/0` to the NAT instance's network interface; the isolated ones have no
  default route. The community module `RaJiska/fck-nat/aws` builds the instance, its security group (allow the VPC
  CIDR in) and the routes (`update_route_tables`), and can run it self-healing (`ha_mode`).
- Put the S3 gateway endpoint on the private route tables.
