# network

A VPC across two availability zones with three subnet tiers: public (the ALB, the NAT instance), private (the
ECS tasks, no public IPs) and isolated (RDS, no route out). An internet gateway, route tables, a NAT instance
(fck-nat on a t4g.nano) for the private subnets' outbound traffic, and the S3 gateway endpoint so ECR image
layers skip the NAT.

Used by: each environment (environments/*/main.tf).

| | |
|---|---|
| Inputs | name prefix, VPC CIDR, availability zones, NAT instance type, tags |
| Outputs | VPC id and CIDR, public / private / isolated subnet ids, NAT instance id |
