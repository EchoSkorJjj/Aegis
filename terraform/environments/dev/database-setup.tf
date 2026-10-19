# The one-time database setup (environments/README.md): a task that runs psql as the master user from the private
# subnets, since nothing outside the VPC reaches the database. It creates the aegis database and the two roles the
# services connect as, with the passwords in their SSM parameters. Running it again changes nothing but the two
# passwords, set again from the parameters: that is how to rotate them.
#
# RDS has no superuser, so on PostgreSQL 16 the master user may create a database owned by another role only once
# it is a member of that role, hence the grant.

locals {
  database_setup_name = "${local.name}-database-setup"

  database_setup_script = <<-EOT
    set -eu
    psql -v ON_ERROR_STOP=1 -v identity_password="$IDENTITY_DB_PASSWORD" -v core_password="$CORE_DB_PASSWORD" <<'SQL'
    select format('create role %I login', wanted.rolname)
      from unnest(array['aegis_identity', 'aegis_core']) as wanted(rolname)
      where not exists (select from pg_roles where pg_roles.rolname = wanted.rolname) \gexec
    alter role aegis_identity with login password :'identity_password';
    alter role aegis_core with login password :'core_password';
    grant aegis_identity to current_user;
    select 'create database aegis owner aegis_identity'
      where not exists (select from pg_database where datname = 'aegis') \gexec
    set role aegis_identity;
    grant create on database aegis to aegis_core;
    reset role;
    SQL
    echo "Database setup done: database aegis, roles aegis_identity and aegis_core."
  EOT
}

resource "aws_cloudwatch_log_group" "database_setup" {
  name              = "/ecs/${local.database_setup_name}"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Its own execution role, so the services' shared one never reads the master password.
resource "aws_iam_role" "database_setup_execution" {
  name               = "${local.database_setup_name}-execution"
  description        = "Starts the ${local.name} database setup task: writes its logs, reads the master and service passwords"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

data "aws_iam_policy_document" "database_setup_execution" {
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.database_setup.arn}:*"]
  }

  # The master secret uses the AWS-managed aws/secretsmanager key and the parameters aws/ssm: no kms:Decrypt needed.
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [module.rds.master_user_secret_arn]
  }

  statement {
    actions = ["ssm:GetParameters"]
    resources = [
      module.secrets.parameter_arns["identity/db-password"],
      module.secrets.parameter_arns["core/db-password"],
    ]
  }
}

resource "aws_iam_role_policy" "database_setup_execution" {
  name   = "database-setup"
  role   = aws_iam_role.database_setup_execution.id
  policy = data.aws_iam_policy_document.database_setup_execution.json
}

resource "aws_ecs_task_definition" "database_setup" {
  family                   = local.database_setup_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.database_setup_execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name       = "psql"
    image      = "public.ecr.aws/docker/library/postgres:16-alpine"
    essential  = true
    entryPoint = ["sh", "-c"]
    command    = [local.database_setup_script]
    environment = [
      { name = "PGHOST", value = module.rds.address },
      { name = "PGPORT", value = tostring(module.rds.port) },
      { name = "PGDATABASE", value = "postgres" },
      { name = "PGSSLMODE", value = "require" },
      { name = "PGCONNECT_TIMEOUT", value = "10" },
    ]
    secrets = [
      { name = "PGUSER", valueFrom = "${module.rds.master_user_secret_arn}:username::" },
      { name = "PGPASSWORD", valueFrom = "${module.rds.master_user_secret_arn}:password::" },
      { name = "IDENTITY_DB_PASSWORD", valueFrom = module.secrets.parameter_arns["identity/db-password"] },
      { name = "CORE_DB_PASSWORD", valueFrom = module.secrets.parameter_arns["core/db-password"] },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.database_setup.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "psql"
      }
    }
  }])
}
