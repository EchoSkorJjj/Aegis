# rds-postgres

PostgreSQL 16 in the isolated subnets: the instance, its subnet group and parameter group.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | Name prefix, e.g. `aegis-dev` |
| `subnet_ids` | `list(string)` | — | The isolated subnets |
| `security_group_ids` | `list(string)` | — | The database's security group |
| `engine_version` | `string` | — | `"16"` (RDS picks the minor version and upgrades it) |
| `instance_class` | `string` | — | e.g. `db.t4g.micro` |
| `allocated_storage` | `number` | — | GB |
| `max_allocated_storage` | `number` | — | Storage autoscaling ceiling, GB |
| `master_username` | `string` | — | e.g. `aegis_admin` |
| `backup_retention_period` | `number` | — | Days |
| `deletion_protection` | `bool` | — | Refuse deletion |
| `skip_final_snapshot` | `bool` | — | No snapshot when deleted |

## Outputs

| Name | Type | Description |
|---|---|---|
| `address` | `string` | The endpoint's host name |
| `port` | `number` | The endpoint's port |
| `master_user_secret_arn` | `string` | The Secrets Manager secret holding the master username and password |
| `instance_id` | `string` | The DB instance's identifier |

## Notes

- `manage_master_user_password = true`: RDS keeps the master password in Secrets Manager (under the AWS-managed
  `aws/secretsmanager` key) and rotates it every 7 days, so no password is in Terraform's state. The rotation
  doesn't reach the services: they connect as their own roles, which the environment's one-time database setup
  task creates (`environments/README.md`).
- No initial database (`db_name` unset): that same task creates `aegis`.
- gp3, encrypted with the AWS-managed `aws/rds` key, not publicly accessible, single-AZ. `rds.force_ssl = 1`
  (already PostgreSQL 16's default on RDS).
- Backups 02:00–03:00 and maintenance Monday 03:30–04:30, Singapore time.
- With `skip_final_snapshot = false`, deleting the instance leaves a snapshot `<name>-final`.
