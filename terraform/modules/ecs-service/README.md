# ecs-service

One service: its task definition (image, CPU and memory, environment, secrets from SSM, JAVA_TOOL_OPTIONS for
the JVMs, logs), the ECS service (capacity provider strategy, desired count, deployment settings, private
subnets, security group, and optionally the ALB target group), its service discovery registration, log group
and task role.

Used by: each environment, five times (environments/*/services.tf).

| | |
|---|---|
| Inputs | name, cluster ARN, image, container port, CPU, memory, environment, secrets (name to SSM ARN), subnet ids, security group id, namespace id, execution role ARN, capacity type, desired count, optional target group ARN, health check, tags |
| Outputs | service name and ARN, task definition ARN, task role ARN, service discovery host name |
