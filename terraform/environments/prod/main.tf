# This environment's platform, composed from ../../modules. The services that run on it are in services.tf.

module "network" {
  source = "../../modules/network"

  name               = local.name
  vpc_cidr           = var.vpc_cidr
  availability_zones = local.availability_zones
}

module "security_groups" {
  source = "../../modules/security-groups"

  name          = local.name
  vpc_id        = module.network.vpc_id
  service_ports = local.ports
  database_port = 5432
}

module "rds" {
  source = "../../modules/rds-postgres"

  name                    = local.name
  subnet_ids              = module.network.isolated_subnet_ids
  security_group_ids      = [module.security_groups.database_security_group_id]
  engine_version          = "16"
  instance_class          = var.db_instance_class
  allocated_storage       = var.db_allocated_storage
  max_allocated_storage   = var.db_max_allocated_storage
  master_username         = "aegis_admin"
  backup_retention_period = var.db_backup_retention_days
  deletion_protection     = var.deletion_protection
  skip_final_snapshot     = !var.deletion_protection
}

module "secrets" {
  source = "../../modules/ssm-secrets"

  prefix = "/aegis/${var.environment}"
  names  = local.secret_names
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  name           = local.name
  vpc_id         = module.network.vpc_id
  namespace_name = local.namespace
  secret_arns    = values(module.secrets.parameter_arns)
}

module "alb" {
  source = "../../modules/alb"

  name               = local.name
  vpc_id             = module.network.vpc_id
  subnet_ids         = module.network.public_subnet_ids
  security_group_ids = [module.security_groups.alb_security_group_id]
  zone_id            = data.aws_route53_zone.primary.zone_id
  domain_name        = var.domain_name
  idle_timeout       = var.alb_idle_timeout
  target_port        = local.ports.frontend
  health_check_path  = local.frontend_health_check_path
}
