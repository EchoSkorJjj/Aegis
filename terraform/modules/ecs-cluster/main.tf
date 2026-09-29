# The cluster the five services run in, the private DNS namespace they find each other through
# (gateway.dev.aegis.internal:8080 and so on), and the task execution role every task shares: it pulls images,
# writes logs and reads the services' secrets when a task starts.

data "aws_partition" "current" {}

resource "aws_ecs_cluster" "this" {
  name = var.name

  # Container Insights costs extra; the services' logs and ECS's own metrics are enough to start with.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name = var.namespace_name
  vpc  = var.vpc_id
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

resource "aws_iam_role" "execution" {
  name               = "${var.name}-task-execution"
  description        = "Pulls the images, writes the logs and reads the secrets of the ${var.name} tasks"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# The parameters are SecureStrings under the AWS-managed aws/ssm key, which lets any principal of the account
# decrypt through SSM: no kms:Decrypt grant needed.
data "aws_iam_policy_document" "read_secrets" {
  statement {
    actions   = ["ssm:GetParameters"]
    resources = var.secret_arns
  }
}

resource "aws_iam_role_policy" "read_secrets" {
  count = length(var.secret_arns) > 0 ? 1 : 0

  name   = "read-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_secrets.json
}
