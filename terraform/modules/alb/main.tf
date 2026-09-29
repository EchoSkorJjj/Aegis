# The internet-facing load balancer. Its only target is the frontend: the browser never calls the gateway directly.

resource "aws_lb" "this" {
  name               = var.name
  load_balancer_type = "application"
  internal           = false
  subnets            = var.subnet_ids
  security_groups    = var.security_group_ids
  idle_timeout       = var.idle_timeout

  drop_invalid_header_fields = true
  # Appends the client's real address to whatever X-Forwarded-For the client sent: the frontend's BFF takes that
  # last entry (TRUSTED_PROXY_HOPS=1).
  xff_header_processing_mode = "append"
}

resource "aws_lb_target_group" "frontend" {
  name                 = "${var.name}-frontend"
  port                 = var.target_port
  protocol             = "HTTP"
  target_type          = "ip" # Fargate tasks register by IP
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = var.health_check_path
    matcher             = var.health_check_matcher
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# The certificate, validated through DNS records in the zone. This waits until the zone answers for the domain,
# i.e. until the registrar points at it.

resource "aws_acm_certificate" "this" {
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "validation" {
  for_each = {
    for option in aws_acm_certificate.this.domain_validation_options : option.domain_name => option
  }

  zone_id         = var.zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  ttl             = 60
  records         = [each.value.resource_record_value]
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.this.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_route53_record" "alias" {
  zone_id = var.zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}
