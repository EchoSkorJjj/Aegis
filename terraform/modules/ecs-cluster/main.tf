# The ECS cluster with the FARGATE and FARGATE_SPOT capacity providers, the Cloud Map private DNS namespace the
# services find each other in (gateway.<namespace>:8080 and so on), and the task execution role shared by every
# task: pull from ECR, write logs, read this environment's SSM parameters.
