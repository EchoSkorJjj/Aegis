# ecs-service

One service: its task definition, the ECS service, its service discovery registration, log group and task role.

Used by: each environment, once per service (`environments/*/services.tf`, `for_each` over the five).

## Inputs

| Name | Type | Default | Description |
|---|---|---|---|
| `name` | `string` | — | e.g. `aegis-dev-gateway`: service, task family and log group |
| `cluster_arn` | `string` | — | The cluster |
| `execution_role_arn` | `string` | — | The shared task execution role |
| `namespace_id` | `string` | — | The Cloud Map namespace |
| `discovery_name` | `string` | — | The name registered in it, e.g. `gateway` |
| `image` | `string` | — | `<repository url>:<tag>`, built `linux/amd64` |
| `container_port` | `number` | — | The port the container listens on |
| `cpu` | `number` | — | Task CPU units (256, 512, 1024) |
| `memory` | `number` | — | Task memory, MiB (a valid pair with `cpu`) |
| `environment` | `map(string)` | — | Plain environment variables; empty values are left out |
| `secrets` | `map(string)` | — | Environment variable => SSM parameter ARN |
| `subnet_ids` | `list(string)` | — | The private subnets |
| `security_group_ids` | `list(string)` | — | The service's security group |
| `capacity_provider` | `string` | — | `FARGATE` or `FARGATE_SPOT` |
| `desired_count` | `number` | — | Tasks to run (0 stops the service) |
| `load_balanced` | `bool` | `false` | Registers the tasks with `target_group_arn` (the frontend only) |
| `target_group_arn` | `string` | `null` | The ALB target group, required when `load_balanced` |
| `log_retention_days` | `number` | — | CloudWatch Logs retention |

`load_balanced` is a separate flag because the target group's ARN isn't known until it's created, and a block can't
be switched on a value Terraform only learns at apply.

## Outputs

| Name | Type | Description |
|---|---|---|
| `service_name` | `string` | The ECS service's name |
| `task_definition_arn` | `string` | The task definition revision the service runs |
| `task_role_arn` | `string` | The application's own role (no permissions yet) |
| `discovery_host` | `string` | The name the others reach it at, without the namespace |

## Notes

- Task definition: Fargate, `awsvpc`, Linux **X86_64** (Fargate Spot runs no ARM tasks). One container, `app`;
  `environment` and `secrets` sorted by name, so the JSON only changes when a value does. Logs go to
  `/ecs/<name>`.
- No container health check: not every image has curl or wget (aegis-core removes curl once syft is installed;
  `python:3.12-slim` has neither). ECS replaces a task whose process exits, and the ALB checks the frontend.
- Deploys roll: the new task starts before the old one stops (100% minimum healthy, 200% maximum), and the
  circuit breaker rolls back a deploy whose tasks keep failing to start.
- Service discovery: an A record per running task, TTL 10, at `<discovery_name>.<namespace>`.
- A service reads its environment and secrets only when a task starts: after changing a secret, redeploy with
  `aws ecs update-service --cluster <cluster> --service <name> --force-new-deployment`.
