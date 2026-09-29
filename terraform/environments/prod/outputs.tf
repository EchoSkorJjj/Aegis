output "app_url" {
  description = "Where this environment serves."
  value       = local.app_url
}

output "alb_dns_name" {
  description = "The load balancer's own name (the app URL is an alias of it)."
  value       = module.alb.alb_dns_name
}

output "database_endpoint" {
  description = "host:port of the database."
  value       = "${module.rds.address}:${module.rds.port}"
}

output "database_master_secret_arn" {
  description = "The Secrets Manager secret holding the master username and password (RDS rotates it)."
  value       = module.rds.master_user_secret_arn
}

output "database_setup_command" {
  description = "Runs the one-time database setup (database-setup.tf); again to reset the services' database passwords."
  value = join(" ", [
    "aws ecs run-task --region ${var.aws_region} --cluster ${module.ecs_cluster.cluster_name}",
    "--launch-type FARGATE --task-definition ${aws_ecs_task_definition.database_setup.family}",
    "--network-configuration 'awsvpcConfiguration={subnets=[${join(",", module.network.private_subnet_ids)}],securityGroups=[${module.security_groups.service_security_group_ids["aegis-core"]}],assignPublicIp=DISABLED}'",
    "--query 'tasks[0].taskArn' --output text",
  ])
}

output "database_setup_log_group" {
  description = "Where the database setup task logs: aws logs tail <this> --follow."
  value       = aws_cloudwatch_log_group.database_setup.name
}

output "ecs_cluster_name" {
  description = "The ECS cluster the services run in."
  value       = module.ecs_cluster.cluster_name
}

output "service_names" {
  description = "Each service's ECS service name."
  value       = { for service, deployed in module.service : service => deployed.service_name }
}

output "secret_parameter_names" {
  description = "The SSM parameters to fill in by hand: aws ssm put-parameter --name <name> --type SecureString --overwrite --value …"
  value       = module.secrets.parameter_names
}
