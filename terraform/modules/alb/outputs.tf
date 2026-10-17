output "alb_dns_name" {
  description = "The ALB's own DNS name."
  value       = aws_lb.this.dns_name
}

output "target_group_arn" {
  description = "The frontend's target group."
  value       = aws_lb_target_group.frontend.arn

  # ECS refuses a target group no listener uses, and the frontend's service is created from this output.
  depends_on = [aws_lb_listener.https]
}

output "url" {
  description = "The app's address."
  value       = "https://${var.domain_name}"
}
