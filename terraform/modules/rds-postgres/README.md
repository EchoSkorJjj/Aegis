# rds-postgres

PostgreSQL 16 in the isolated subnets: the instance (db.t4g.micro, Single-AZ, encrypted, 7-day backups,
storage autoscaling, deletion protection in prod), its subnet group and parameter group. The master password
is write-only, so it never reaches state; the services' own database roles are created once afterwards
(todo/aws-deployment.md).

Used by: each environment (environments/*/main.tf).

| | |
|---|---|
| Inputs | name prefix, isolated subnet ids, security group id, instance class, storage, engine version, deletion protection, master password (write-only), tags |
| Outputs | endpoint, port, database name, instance id |
