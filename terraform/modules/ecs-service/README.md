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
| `image` | `string` | — | `<repository url>:<tag>` |
| `container_port` | `number` | — | The port the container listens on |
| `cpu` | `number` | — | Task CPU units (256, 512, 1024) |
| `memory` | `number` | — | Task memory, MiB (a valid pair with `cpu`) |
| `environment` | `map(string)` | — | Plain environment variables |
| `secrets` | `map(string)` | — | Environment variable => SSM parameter ARN |
| `subnet_ids` | `list(string)` | — | The private subnets |
| `security_group_ids` | `list(string)` | — | The service's security group |
| `capacity_provider` | `string` | — | `FARGATE` or `FARGATE_SPOT` |
| `desired_count` | `number` | — | Tasks to run (0 stops the service) |
| `health_check_path` | `string` | — | The service's health path (`/actuator/health`, `/` for the frontend) |
| `target_group_arn` | `string` | `null` | The ALB target group to register with (the frontend only) |
| `log_retention_days` | `number` | — | CloudWatch Logs retention |

## Outputs

| Name | Type | Description |
|---|---|---|
| `service_name` | `string` | The ECS service's name |

## Notes

- Task definition: `requires_compatibilities = ["FARGATE"]`, `network_mode = "awsvpc"`, `runtime_platform` Linux
  **X86_64** (Fargate Spot runs no ARM tasks, so build the images `linux/amd64`). One container; `environment` and
  `secrets` turn into its `environment` / `secrets` lists (`name` + `value` / `valueFrom`); `awslogs` log driver to
  its own `aws_cloudwatch_log_group` (`/ecs/<name>`).
- Service: `capacity_provider_strategy` with `var.capacity_provider`, `network_configuration` without a public IP,
  `service_registries` pointing at an `aws_service_discovery_service` (an A record, TTL 10) named
  `discovery_name`, `deployment_circuit_breaker { enable = true, rollback = true }`.
- Only when `target_group_arn` isn't null: a `dynamic "load_balancer"` block, and
  `health_check_grace_period_seconds` (about 120: the JVMs take a while to start).
- Task role: trusted by `ecs-tasks.amazonaws.com`, no permissions yet (no service calls AWS APIs).
- Leave the container health check out: not every image has curl or wget (aegis-core removes curl once syft is
  installed; `python:3.12-slim` has neither). ECS replaces a task whose process exits, and the ALB checks the
  frontend.
