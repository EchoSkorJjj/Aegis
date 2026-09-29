# security-groups

One security group per tier and the rules between them, so each service accepts only its callers.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | Name prefix, e.g. `aegis-dev` |
| `vpc_id` | `string` | — | The VPC |
| `service_ports` | `map(number)` | — | Each service's port: keys `frontend`, `gateway`, `identity-service`, `aegis-core`, `ai-service` |
| `database_port` | `number` | — | PostgreSQL's port (5432) |

## Outputs

| Name | Type | Description |
|---|---|---|
| `alb_security_group_id` | `string` | For the ALB |
| `service_security_group_ids` | `map(string)` | For each service, keyed like `service_ports` |
| `database_security_group_id` | `string` | For RDS |

## Rules

| To | Port | From |
|---|---|---|
| ALB | 443, 80 | anywhere (`0.0.0.0/0`, `::/0`) |
| frontend | 3000 | ALB |
| gateway | 8080 | frontend |
| identity-service | 8081 | gateway, aegis-core |
| aegis-core | 8082 | gateway |
| ai-service | 8083 | aegis-core |
| database | 5432 | identity-service, aegis-core |

Every service may go out anywhere (the NAT instance takes it from there); the ALB only to the frontend. Use
`aws_vpc_security_group_ingress_rule` / `aws_vpc_security_group_egress_rule` with `referenced_security_group_id`,
not inline `ingress` blocks. Only the gateway and aegis-core may reach identity-service: it trusts the first
`X-Forwarded-For` hop.

The environment's one-time database setup task runs in aegis-core's group, the one group besides identity-service's
that reaches the database.
