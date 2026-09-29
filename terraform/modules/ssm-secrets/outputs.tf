output "parameter_arns" {
  description = "Name => ARN, for the task definitions' secrets and the execution role."
  value = {
    for name, path in local.paths :
    name => "arn:${data.aws_partition.current.partition}:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter${path}"
  }
}

output "parameter_names" {
  description = "Name => full path (/aegis/dev/identity/jwt-private-key), for creating them."
  value       = local.paths
}
