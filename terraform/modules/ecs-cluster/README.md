# ecs-cluster

The ECS cluster with the FARGATE and FARGATE_SPOT capacity providers, the Cloud Map private DNS namespace the
services find each other in (gateway.<namespace>:8080 and so on), and the task execution role shared by every
task: pull from ECR, write logs, read this environment's SSM parameters.

Used by: each environment (environments/*/main.tf).

| | |
|---|---|
| Inputs | name prefix, VPC id, namespace name, SSM parameter ARNs the tasks may read, tags |
| Outputs | cluster ARN and name, namespace id and name, execution role ARN |
