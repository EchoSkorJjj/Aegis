output "cluster_arn" {
  description = "The cluster."
  value       = aws_ecs_cluster.this.arn

  # A service naming FARGATE_SPOT fails if it's created before the cluster has the capacity provider.
  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

output "cluster_name" {
  description = "The cluster's name."
  value       = aws_ecs_cluster.this.name
}

output "namespace_id" {
  description = "The Cloud Map namespace."
  value       = aws_service_discovery_private_dns_namespace.this.id
}

output "namespace_name" {
  description = "The namespace's DNS name."
  value       = aws_service_discovery_private_dns_namespace.this.name
}

output "execution_role_arn" {
  description = "The task execution role."
  value       = aws_iam_role.execution.arn
}
