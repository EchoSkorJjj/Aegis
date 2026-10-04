output "hcp_terraform_role_arn" {
  description = "The role HCP Terraform's runs assume (already set as TFC_AWS_RUN_ROLE_ARN on the workspaces)."
  value       = aws_iam_role.hcp_terraform.arn
}

output "workspace_ids" {
  description = "The workspaces created, by name."
  value       = { for name, workspace in tfe_workspace.this : name => workspace.id }
}
