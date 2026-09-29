# ssm-secrets

The names and ARNs of the SecureString parameters the services read their secrets from. It creates nothing: a
person creates each parameter, so no secret ever passes through Terraform, its state or a tfvars file.

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
| `parameter_names` | `map(string)` | Name => full path (`/aegis/dev/identity/jwt-private-key`), for creating them |

## Notes

- Why no `aws_ssm_parameter`: the resource reads a parameter's value back into state on every refresh, even with
  `ignore_changes = [value]` and a placeholder Terraform set. Write-only values (`value_wo`) avoid that, but then
  Terraform would create each secret with a placeholder the services would happily start with; a parameter that
  doesn't exist instead stops a task at start, naming it.
- Create each with `aws ssm put-parameter --name <path> --type SecureString --value '…'` (`--value file://key.pem`
  for a PEM; add `--overwrite` to change one). They use the AWS-managed `aws/ssm` key, so there is no KMS key to
  create and the execution role needs no `kms:Decrypt`. `environments/README.md` has every command.
- A service reads its secrets only when a task starts: after changing one, redeploy the service
  (`aws ecs update-service --force-new-deployment`).
- `terraform destroy` leaves the parameters: delete them by hand if the environment is gone for good.
