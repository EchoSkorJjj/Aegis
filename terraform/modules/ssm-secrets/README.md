# ssm-secrets

The SecureString parameters the services read their secrets from. Terraform creates each parameter; a person
sets its value, so no secret is ever in Terraform's state or a tfvars file.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `prefix` | `string` | — | e.g. `/aegis/dev` |
| `names` | `set(string)` | — | Parameter names under the prefix, e.g. `identity/jwt-private-key` |

## Outputs

| Name | Type | Description |
|---|---|---|
| `parameter_arns` | `map(string)` | Name => ARN, for the task definitions' `secrets` and the execution role |
| `parameter_names` | `map(string)` | Name => full path (`/aegis/dev/identity/jwt-private-key`), for filling them in |

## Notes

- `aws_ssm_parameter`, `type = "SecureString"` (the AWS-managed `aws/ssm` key), with a placeholder `value` (SSM
  refuses an empty one) and `lifecycle { ignore_changes = [value] }`, so Terraform never overwrites what a person
  set. Fill each in with `aws ssm put-parameter --name <path> --type SecureString --overwrite --value '…'`
  (`file://key.pem` for a PEM).
- A service reads its secrets only when a task starts: after changing one, redeploy the service.
