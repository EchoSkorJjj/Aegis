output "identity_arn" {
  description = "The SES identity the domain sends as."
  value       = aws_sesv2_email_identity.this.arn
}

output "smtp_endpoint" {
  description = "SES's SMTP endpoint in this region: identity-service's SPRING_MAIL_HOST."
  value       = "email-smtp.${data.aws_region.current.region}.amazonaws.com"
}
