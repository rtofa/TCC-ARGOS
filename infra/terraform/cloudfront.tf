# HTTPS sem domínio próprio: CloudFront na frente do ALB, com o certificado padrão *.cloudfront.net, e o navegador
# deixa de mostrar "Não seguro". O ALB passa a aceitar só o CloudFront (network.tf). Com domain_name preenchido, o
# HTTPS fica no próprio ALB (dns.tf) e o CloudFront não é criado. Custo: dentro da cota gratuita permanente.

locals {
  use_cloudfront = var.enable_cloudfront && !local.use_https
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewerExceptHostHeader"
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  count = local.use_cloudfront ? 1 : 0
  name  = "com.amazonaws.global.cloudfront.origin-facing"
}

# API: nada fica em cache (TTL padrão 0, e o Spring Security ainda responde Cache-Control: no-store), mas o
# Authorization e o X-API-Key entram na chave de cache. É o jeito garantido de o CloudFront repassá-los também em GET:
# para GET e HEAD, ele tira o Authorization das requisições a origens customizadas quando o cabeçalho não está na chave.
resource "aws_cloudfront_cache_policy" "api" {
  count       = local.use_cloudfront ? 1 : 0
  name        = "${local.name}-api"
  comment     = "API do Argos: repassa Authorization e X-API-Key, sem cache"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 1

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = false
    enable_accept_encoding_gzip   = false

    headers_config {
      header_behavior = "whitelist"
      headers {
        items = ["Authorization", "X-API-Key"]
      }
    }

    cookies_config {
      cookie_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "all"
    }
  }
}

resource "aws_cloudfront_distribution" "this" {
  count           = local.use_cloudfront ? 1 : 0
  enabled         = true
  comment         = "${local.name}: HTTPS na frente do ALB"
  price_class     = "PriceClass_All" # inclui as localizações da América do Sul (menor latência no Brasil)
  http_version    = "http2"
  is_ipv6_enabled = true

  origin {
    origin_id   = "alb"
    domain_name = aws_lb.this.dns_name

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only" # sem domínio, o ALB só tem HTTP; o trecho público é HTTPS
      origin_ssl_protocols   = ["TLSv1.2"]
      # Tempo máximo sem dados vindos do backend (limite padrão da AWS). O streaming da Lia manda um comentário SSE
      # a cada 15 s enquanto o modelo pensa, então não chega perto disso.
      origin_read_timeout      = 60
      origin_keepalive_timeout = 60
    }
  }

  # SPA (index.html e rotas do React): sem cache, para um deploy novo aparecer na hora.
  default_cache_behavior {
    target_origin_id       = "alb"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.disabled.id
    compress               = true
  }

  # JS e CSS com hash no nome: cache longo, sem risco de servir versão velha.
  ordered_cache_behavior {
    path_pattern           = "/assets/*"
    target_origin_id       = "alb"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.optimized.id
    compress               = true
  }

  # API (inclusive o streaming da Lia e os webhooks) e health check: tudo repassado, nada em cache, sem compressão
  # (compressão seguraria os eventos do streaming). Só HTTPS: um POST redirecionado viraria GET sem aviso.
  dynamic "ordered_cache_behavior" {
    for_each = ["/api/*", "/actuator/health*"]
    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = "alb"
      viewer_protocol_policy   = "https-only"
      allowed_methods          = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
      cached_methods           = ["GET", "HEAD"]
      cache_policy_id          = aws_cloudfront_cache_policy.api[0].id
      origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
      compress                 = false
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
