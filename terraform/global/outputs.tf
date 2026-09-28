output "zone_id" {
  description = "The domain's Route 53 zone."
  value       = aws_route53_zone.primary.zone_id
}

output "name_servers" {
  description = "Set these at the registrar if the domain isn't registered with Route 53."
  value       = aws_route53_zone.primary.name_servers
}

output "ecr_repository_urls" {
  description = "Where CI pushes each service's image."
  value       = { for service, repository in aws_ecr_repository.service : service => repository.repository_url }
}

output "github_actions_role_arn" {
  description = "The role-to-assume for aws-actions/configure-aws-credentials in the image workflow."
  value       = aws_iam_role.github_actions.arn
}

output "ses_identity_arn" {
  description = "The SES identity the domain sends as."
  value       = module.ses_domain.identity_arn
}

output "ses_smtp_endpoint" {
  description = "identity-service's SPRING_MAIL_HOST."
  value       = module.ses_domain.smtp_endpoint
}
