# What global made, found by name rather than through its state.

data "aws_availability_zones" "available" {
  state = "available"

  # Regular zones only, not Local Zones.
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

data "aws_route53_zone" "primary" {
  name = var.root_domain
}

data "aws_ecr_repository" "service" {
  for_each = local.ports

  name = "aegis/${each.key}"
}
