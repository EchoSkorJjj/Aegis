# rds-postgres

PostgreSQL 16 in the isolated subnets: the instance, its subnet group and parameter group.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | Name prefix, e.g. `aegis-dev` |
| `subnet_ids` | `list(string)` | — | The isolated subnets |
| `security_group_ids` | `list(string)` | — | The database's security group |
| `engine_version` | `string` | — | `"16"` (RDS picks the minor version) |
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
| `master_user_secret_arn` | `string` | The Secrets Manager secret holding the master password |

## Notes

- `manage_master_user_password = true`: RDS keeps the master password in Secrets Manager and rotates it, so no
  password is in Terraform's state. The rotation doesn't reach the services: they connect as their own roles.
- No initial database (`db_name` unset): the one-time setup, run as the master user, creates the `aegis` database
  and the `aegis_identity` and `aegis_core` roles (`todo/aws-deployment.md`).
- `storage_type = "gp3"`, `storage_encrypted = true`, `publicly_accessible = false`, `multi_az = false`,
  `auto_minor_version_upgrade = true`; `final_snapshot_identifier` when `skip_final_snapshot` is false.
- Parameter group family `postgres16` (RDS already forces TLS on PostgreSQL 16).
