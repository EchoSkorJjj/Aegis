# One service: its task definition (image, CPU and memory, environment, secrets from SSM, JAVA_TOOL_OPTIONS for
# the JVMs, logs), the ECS service (capacity provider strategy, desired count, deployment settings, private
# subnets, security group, and optionally the ALB target group), its service discovery registration, log group
# and task role.
