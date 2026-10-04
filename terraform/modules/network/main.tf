# A VPC across two availability zones with three subnet tiers, /20s carved from the /16 in this order:
#   public    the ALB and the NAT instance; routes to the internet gateway
#   private   the ECS tasks, with no public IPs; routes out through the NAT instance
#   isolated  RDS; no route out at all
# Outbound traffic from the private subnets goes through a NAT instance (a t4g.nano, about $3 a month) rather than a
# NAT Gateway (about $33 before data). It runs Amazon Linux 2023 from the AMI AWS publishes in SSM, not a
# third-party image. The S3 gateway endpoint lets ECR image layers skip it.

data "aws_region" "current" {}

locals {
  zones = { for index, zone in var.availability_zones : zone => index }
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # service discovery and the RDS endpoint resolve through the VPC's DNS

  tags = { Name = var.name }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = var.name }
}

resource "aws_subnet" "public" {
  for_each = local.zones

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, each.value)

  tags = { Name = "${var.name}-public-${each.key}" }
}

resource "aws_subnet" "private" {
  for_each = local.zones

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, 2 + each.value)

  tags = { Name = "${var.name}-private-${each.key}" }
}

resource "aws_subnet" "isolated" {
  for_each = local.zones

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, 4 + each.value)

  tags = { Name = "${var.name}-isolated-${each.key}" }
}

# Route tables: public to the internet gateway, private to the NAT instance, isolated nowhere.

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = { Name = "${var.name}-public" }
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-private" }
}

resource "aws_route" "private_out" {
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  network_interface_id   = aws_instance.nat.primary_network_interface_id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table" "isolated" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-isolated" }
}

resource "aws_route_table_association" "isolated" {
  for_each = aws_subnet.isolated

  subnet_id      = each.value.id
  route_table_id = aws_route_table.isolated.id
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = { Name = "${var.name}-s3" }
}

# The NAT instance: forwards the private subnets' traffic and masquerades it behind its own public IP.

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

resource "aws_security_group" "nat" {
  name        = "${var.name}-nat"
  description = "NAT instance: traffic from inside the VPC, out to anywhere"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name}-nat" }
}

resource "aws_vpc_security_group_ingress_rule" "nat_from_vpc" {
  security_group_id = aws_security_group.nat.id
  description       = "Anything from inside the VPC, to be forwarded"
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "-1"
}

resource "aws_vpc_security_group_egress_rule" "nat_out" {
  security_group_id = aws_security_group.nat.id
  description       = "Out to the internet"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_instance" "nat" {
  ami                         = data.aws_ssm_parameter.al2023_arm64.value
  instance_type               = var.nat_instance_type
  subnet_id                   = aws_subnet.public[var.availability_zones[0]].id
  vpc_security_group_ids      = [aws_security_group.nat.id]
  associate_public_ip_address = true
  source_dest_check           = false # it forwards traffic that isn't addressed to it

  metadata_options {
    http_tokens = "required"
  }

  # Turns on forwarding and masquerades what leaves through the default interface. EC2 recovers the instance
  # itself (same interface, same routes) if its host fails.
  user_data                   = <<-EOT
    #!/bin/bash
    set -euo pipefail
    for attempt in 1 2 3 4 5; do dnf install -y iptables-services && break; sleep 15; done
    echo 'net.ipv4.ip_forward = 1' > /etc/sysctl.d/90-nat.conf
    sysctl --system
    systemctl enable --now iptables
    iface="$(ip -o -4 route show to default | awk '{print $5}' | head -n1)"
    iptables -F
    iptables -t nat -F
    iptables -P FORWARD ACCEPT
    iptables -t nat -A POSTROUTING -o "$iface" -j MASQUERADE
    iptables-save > /etc/sysconfig/iptables
  EOT
  user_data_replace_on_change = true

  tags = { Name = "${var.name}-nat" }

  lifecycle {
    # A newer AMI in SSM shouldn't replace a working NAT on every apply; replace it deliberately (terraform apply
    # -replace) to pick one up.
    ignore_changes = [ami]
  }
}
