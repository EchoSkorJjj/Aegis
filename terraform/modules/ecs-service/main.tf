# One service on Fargate: its task definition (one container, "app"), the ECS service, its service discovery
# registration, its log group and its task role.

data "aws_region" "current" {}

locals {
  container_name = "app"
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# What the running application may do in AWS: nothing yet (no service calls an AWS API).
resource "aws_iam_role" "task" {
  name               = "${var.name}-task"
  description        = "The ${var.name} application's own AWS permissions"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = aws_iam_role.task.arn

  # Fargate Spot runs no ARM tasks, so the images are built linux/amd64.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  # No container health check: not every image has curl or wget. ECS replaces a task whose process exits, and the
  # ALB checks the frontend. An empty environment value is left out: the applications treat it as unset.
  container_definitions = jsonencode([{
    name      = local.container_name
    image     = var.image
    essential = true
    portMappings = [{
      containerPort = var.container_port
      protocol      = "tcp"
    }]
    environment = [for name in sort(keys(var.environment)) : { name = name, value = var.environment[name] } if var.environment[name] != ""]
    secrets     = [for name in sort(keys(var.secrets)) : { name = name, valueFrom = var.secrets[name] }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.this.name
        "awslogs-region"        = data.aws_region.current.region
        "awslogs-stream-prefix" = local.container_name
      }
    }
  }])
}

# The others reach it as <discovery_name>.<namespace>: an A record per running task.
resource "aws_service_discovery_service" "this" {
  name = var.discovery_name

  dns_config {
    namespace_id   = var.namespace_id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count

  capacity_provider_strategy {
    capacity_provider = var.capacity_provider
    weight            = 1
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.this.arn
  }

  # Rolling deploys: the new task starts before the old one stops (identity-service shares its signing key, and
  # aegis-core's scheduler lease keeps the jobs on one instance), and a deploy that can't start rolls back.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "load_balancer" {
    for_each = var.load_balanced ? [var.target_group_arn] : []

    content {
      target_group_arn = load_balancer.value
      container_name   = local.container_name
      container_port   = var.container_port
    }
  }

  # The ALB's first checks of a starting task don't count against it for this long.
  health_check_grace_period_seconds = var.load_balanced ? 60 : null

  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"
}
