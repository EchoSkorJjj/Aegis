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
  description = "The Secrets Manager secret holding the master password, for the one-time role setup."
  value       = module.rds.master_user_secret_arn
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
