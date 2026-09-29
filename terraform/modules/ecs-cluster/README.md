# ecs-cluster

The ECS cluster with the FARGATE and FARGATE_SPOT capacity providers, the Cloud Map private DNS namespace the
services find each other in (`gateway.dev.aegis.internal:8080` and so on), and the task execution role every task
shares.

Used by: each environment (`environments/*/main.tf`).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | Name prefix, e.g. `aegis-dev` |
| `vpc_id` | `string` | — | The VPC the private namespace answers in |
| `namespace_name` | `string` | — | The namespace, e.g. `dev.aegis.internal` |
| `secret_arns` | `list(string)` | — | The SSM parameters the execution role may read (the services' secrets) |

## Outputs

| Name | Type | Description |
|---|---|---|
| `cluster_arn` | `string` | The cluster |
| `cluster_name` | `string` | The cluster's name |
| `namespace_id` | `string` | The Cloud Map namespace |
| `namespace_name` | `string` | The namespace's DNS name |
| `execution_role_arn` | `string` | The task execution role |

## Notes

- `aws_ecs_cluster` (Container Insights off: it costs extra) + `aws_ecs_cluster_capacity_providers` with
  `FARGATE` and `FARGATE_SPOT`.
- `aws_service_discovery_private_dns_namespace` in `vpc_id`.
- Execution role: trusted by `ecs-tasks.amazonaws.com`; the managed `AmazonECSTaskExecutionRolePolicy` (pull from
  ECR, write logs) plus an inline policy allowing `ssm:GetParameters` on `secret_arns`. The parameters use the
  AWS-managed SSM key, so no `kms:Decrypt` grant is needed.
