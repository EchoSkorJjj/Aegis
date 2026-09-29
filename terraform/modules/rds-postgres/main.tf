# PostgreSQL in the isolated subnets: reachable only from the security groups the database's allows, with no route
# out. RDS keeps the master password in Secrets Manager (manage_master_user_password), so it never passes through
# Terraform or its state. There's no initial database: the one-time setup task creates `aegis` and the services'
# own roles (see environments/README.md).

resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_db_parameter_group" "this" {
  name   = "${var.name}-postgres${var.engine_version}"
  family = "postgres${var.engine_version}"

  # Already the default on PostgreSQL 16; stated so it can't be turned off by accident. RDS reports it as applied at
  # reboot, so it's declared that way: with the provider's default ("immediate"), every plan would show a change.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier     = var.name
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true # the AWS-managed aws/rds key

  username                    = var.master_username
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = var.security_group_ids
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = false

  # Windows in UTC: 02:00–03:00 and Monday 03:30–04:30 in Singapore.
  backup_retention_period    = var.backup_retention_period
  backup_window              = "18:00-19:00"
  maintenance_window         = "sun:19:30-sun:20:30"
  auto_minor_version_upgrade = true
  copy_tags_to_snapshot      = true

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${var.name}-final"
}
