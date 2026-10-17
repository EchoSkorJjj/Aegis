# One security group per tier, and only the traffic Aegis needs between them:
#
#   internet -> ALB :443/:80 -> frontend -> gateway -> identity-service, aegis-core
#                                                      aegis-core -> identity-service, ai-service
#                                          identity-service, aegis-core -> database
#
# Only the gateway and aegis-core reach identity-service: it trusts the first X-Forwarded-For hop it receives.

locals {
  # Who may call each service, on that service's port.
  callers = {
    frontend           = ["alb"]
    gateway            = ["frontend"]
    "identity-service" = ["gateway", "aegis-core"]
    "aegis-core"       = ["gateway"]
    "ai-service"       = ["aegis-core"]
  }

  service_rules = merge([
    for service, callers in local.callers : {
      for caller in callers : "${caller}-to-${service}" => { service = service, caller = caller }
    }
  ]...)

  group_ids = merge(
    { alb = aws_security_group.alb.id },
    { for service, group in aws_security_group.service : service => group.id },
  )
}

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "The load balancer: HTTPS and HTTP from anywhere, to the frontend"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-alb" }
}

resource "aws_security_group" "service" {
  for_each = var.service_ports

  name        = "${var.name}-${each.key}"
  description = "${each.key}: only its callers in, anywhere out (through the NAT instance)"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-${each.key}" }
}

resource "aws_security_group" "database" {
  name        = "${var.name}-database"
  description = "PostgreSQL: only identity-service and aegis-core"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-database" }
}

# The ALB: open to the internet on 443 (and 80, which only redirects), out only to the frontend.

resource "aws_vpc_security_group_ingress_rule" "alb" {
  for_each = {
    "https-ipv4" = { port = 443, cidr_ipv4 = "0.0.0.0/0", cidr_ipv6 = null }
    "https-ipv6" = { port = 443, cidr_ipv4 = null, cidr_ipv6 = "::/0" }
    "http-ipv4"  = { port = 80, cidr_ipv4 = "0.0.0.0/0", cidr_ipv6 = null }
    "http-ipv6"  = { port = 80, cidr_ipv4 = null, cidr_ipv6 = "::/0" }
  }

  security_group_id = aws_security_group.alb.id
  description       = "From the internet"
  ip_protocol       = "tcp"
  from_port         = each.value.port
  to_port           = each.value.port
  cidr_ipv4         = each.value.cidr_ipv4
  cidr_ipv6         = each.value.cidr_ipv6
}

resource "aws_vpc_security_group_egress_rule" "alb_to_frontend" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To the frontend (requests and health checks)"
  ip_protocol                  = "tcp"
  from_port                    = var.service_ports["frontend"]
  to_port                      = var.service_ports["frontend"]
  referenced_security_group_id = aws_security_group.service["frontend"].id
}

# The services: each accepts only its callers, and may go out anywhere.

resource "aws_vpc_security_group_ingress_rule" "service" {
  for_each = local.service_rules

  security_group_id            = aws_security_group.service[each.value.service].id
  description                  = "From ${each.value.caller}"
  ip_protocol                  = "tcp"
  from_port                    = var.service_ports[each.value.service]
  to_port                      = var.service_ports[each.value.service]
  referenced_security_group_id = local.group_ids[each.value.caller]
}

resource "aws_vpc_security_group_egress_rule" "service" {
  for_each = aws_security_group.service

  security_group_id = each.value.id
  description       = "Out: other services, the database, AWS APIs and the internet through the NAT instance"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# The database: PostgreSQL from identity-service and aegis-core only. No egress rule: replies are allowed anyway.

resource "aws_vpc_security_group_ingress_rule" "database" {
  for_each = toset(["identity-service", "aegis-core"])

  security_group_id            = aws_security_group.database.id
  description                  = "From ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = var.database_port
  to_port                      = var.database_port
  referenced_security_group_id = aws_security_group.service[each.key].id
}
