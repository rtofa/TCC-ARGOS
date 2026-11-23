# Opcional: domínio próprio com HTTPS (certificado ACM validado por DNS no Route 53).

resource "aws_acm_certificate" "this" {
  count             = local.use_https ? 1 : 0
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true

    precondition {
      condition     = var.route53_zone_id != ""
      error_message = "Informe route53_zone_id junto com domain_name."
    }
  }
}

# Chave estática (o próprio domínio): os dados de validação só existem depois que o certificado é criado.
resource "aws_route53_record" "certificate_validation" {
  for_each = local.use_https ? toset([var.domain_name]) : toset([])

  zone_id         = var.route53_zone_id
  name            = one([for o in aws_acm_certificate.this[0].domain_validation_options : o.resource_record_name if o.domain_name == each.key])
  type            = one([for o in aws_acm_certificate.this[0].domain_validation_options : o.resource_record_type if o.domain_name == each.key])
  records         = [one([for o in aws_acm_certificate.this[0].domain_validation_options : o.resource_record_value if o.domain_name == each.key])]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "this" {
  count                   = local.use_https ? 1 : 0
  certificate_arn         = aws_acm_certificate.this[0].arn
  validation_record_fqdns = [for record in aws_route53_record.certificate_validation : record.fqdn]
}

resource "aws_route53_record" "app" {
  count   = local.use_https ? 1 : 0
  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}
