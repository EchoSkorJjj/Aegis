output "alb_security_group_id" {
  description = "For the ALB."
  value       = aws_security_group.alb.id
}

output "service_security_group_ids" {
  description = "For each service, keyed like service_ports."
  value       = { for service, group in aws_security_group.service : service => group.id }
}

output "database_security_group_id" {
  description = "For RDS."
  value       = aws_security_group.database.id
}
