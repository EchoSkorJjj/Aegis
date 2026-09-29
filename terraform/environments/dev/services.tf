# The five services, each an ecs-service module. The frontend alone sits behind the load balancer; the others are
# private and reach each other through service discovery (locals.urls).
module "service" {
  source   = "../../modules/ecs-service"
  for_each = local.services

  name               = "${local.name}-${each.key}"
  cluster_arn        = module.ecs_cluster.cluster_arn
  execution_role_arn = module.ecs_cluster.execution_role_arn
  namespace_id       = module.ecs_cluster.namespace_id
  discovery_name     = each.key

  image          = "${data.aws_ecr_repository.service[each.key].repository_url}:${var.image_tag}"
  container_port = local.ports[each.key]
  cpu            = each.value.cpu
  memory         = each.value.memory
  environment    = each.value.environment
  secrets = {
    for variable, parameter in local.service_secrets[each.key] :
    variable => module.secrets.parameter_arns[parameter]
  }

  subnet_ids         = module.network.private_subnet_ids
  security_group_ids = [module.security_groups.service_security_group_ids[each.key]]
  capacity_provider  = var.capacity == "spot" ? "FARGATE_SPOT" : "FARGATE"
  desired_count      = var.desired_count
  load_balanced      = each.key == "frontend"
  target_group_arn   = each.key == "frontend" ? module.alb.target_group_arn : null
  log_retention_days = var.log_retention_days
}
