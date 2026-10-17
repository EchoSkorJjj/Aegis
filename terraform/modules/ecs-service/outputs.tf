output "service_name" {
  description = "The ECS service's name."
  value       = aws_ecs_service.this.name
}

output "task_definition_arn" {
  description = "The task definition revision the service runs."
  value       = aws_ecs_task_definition.this.arn
}

output "task_role_arn" {
  description = "The application's own role."
  value       = aws_iam_role.task.arn
}

output "discovery_host" {
  description = "The name the other services reach it at, without the namespace."
  value       = aws_service_discovery_service.this.name
}
