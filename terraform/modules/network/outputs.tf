output "vpc_id" {
  description = "The VPC."
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "For the ALB."
  value       = [for zone in var.availability_zones : aws_subnet.public[zone].id]
}

output "private_subnet_ids" {
  description = "For the ECS tasks."
  value       = [for zone in var.availability_zones : aws_subnet.private[zone].id]
}

output "isolated_subnet_ids" {
  description = "For RDS."
  value       = [for zone in var.availability_zones : aws_subnet.isolated[zone].id]
}

output "nat_instance_id" {
  description = "The NAT instance."
  value       = aws_instance.nat.id
}
