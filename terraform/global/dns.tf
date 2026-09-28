# The domain's hosted zone. Environments add their own records to it (the ALB alias, certificate validation).
#
# Registered with Route 53? AWS already made a zone for it at registration: import that one before the first
# apply (terraform import aws_route53_zone.primary <zone id>), or the domain keeps pointing at the old zone.
# Registered elsewhere? Set the registrar's name servers to this zone's (the name_servers output).
resource "aws_route53_zone" "primary" {
  name = var.domain_name

  # Recreating it would give the domain new name servers.
  lifecycle {
    prevent_destroy = true
  }
}
