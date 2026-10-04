output "address" {
  description = "The endpoint's host name."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "The endpoint's port."
  value       = aws_db_instance.this.port
}

output "master_user_secret_arn" {
  description = "The Secrets Manager secret holding the master username and password."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "instance_id" {
  description = "The DB instance's identifier."
  value       = aws_db_instance.this.identifier
}
