# Testes da configuração com provedores simulados (não precisam de credenciais nem criam nada na AWS):
#   terraform test
# Conferem que as expressões fecham nos dois cenários: sem domínio e com domínio + chaves de IA + e-mail.

mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["us-east-1a", "us-east-1b", "us-east-1c"]
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_kms_alias" {
    defaults = {
      target_key_arn = "arn:aws:kms:us-east-1:123456789012:key/00000000-0000-0000-0000-000000000000"
    }
  }

  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  # ARNs em formato válido: o provider valida o formato mesmo com recursos simulados.
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }
  mock_resource "aws_ssm_parameter" {
    defaults = { arn = "arn:aws:ssm:us-east-1:123456789012:parameter/argos/prod/mock" }
  }
  mock_resource "aws_lb" {
    defaults = {
      arn      = "arn:aws:elasticloadbalancing:us-east-1:123456789012:loadbalancer/app/argos-prod/abc"
      dns_name = "argos-prod-123.us-east-1.elb.amazonaws.com"
      zone_id  = "Z35SXDOTRQ7X7K"
    }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/argos-prod/abc" }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/app/argos-prod/abc/def" }
  }
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:us-east-1:123456789012:certificate/mock"
      domain_validation_options = [{
        domain_name           = "argos.exemplo.com.br"
        resource_record_name  = "_abc.argos.exemplo.com.br."
        resource_record_type  = "CNAME"
        resource_record_value = "_def.acm-validations.aws."
      }]
    }
  }
  mock_resource "aws_acm_certificate_validation" {
    defaults = { certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/mock" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = {
      arn            = "arn:aws:ecr:us-east-1:123456789012:repository/argos/mock"
      repository_url = "123456789012.dkr.ecr.us-east-1.amazonaws.com/argos/mock"
    }
  }
  mock_resource "aws_ecs_cluster" {
    defaults = {
      arn = "arn:aws:ecs:us-east-1:123456789012:cluster/argos-prod"
      id  = "arn:aws:ecs:us-east-1:123456789012:cluster/argos-prod"
    }
  }
  mock_resource "aws_ecs_task_definition" {
    defaults = { arn = "arn:aws:ecs:us-east-1:123456789012:task-definition/argos-prod:1" }
  }
  mock_resource "aws_db_instance" {
    defaults = { address = "argos-prod.abc.us-east-1.rds.amazonaws.com" }
  }
  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      arn         = "arn:aws:cloudfront::123456789012:distribution/EMOCK"
      domain_name = "d111111abcdef8.cloudfront.net"
    }
  }
  mock_data "aws_ec2_managed_prefix_list" {
    defaults = { id = "pl-3b927c52" }
  }
}

mock_provider "random" {}
mock_provider "tls" {}

run "economico_sem_dominio" {
  command = apply # provedores simulados: nada é criado na AWS

  variables {
    llm_routes = {
      primary = { base_url = "https://api.groq.com/openai/v1", model = "openai/gpt-oss-120b", reasoning_effort = "low" }
    }
    llm_api_keys = { primary = "gsk_teste" }
  }

  assert {
    condition     = length(aws_lb_listener.https) == 0 && length(aws_acm_certificate.this) == 0
    error_message = "Sem domínio não deve haver HTTPS nem certificado."
  }

  assert {
    condition     = aws_ecs_service.backend.desired_count == 1 && aws_ecs_task_definition.backend.cpu == "512"
    error_message = "Os padrões devem ser econômicos (1 task de 0,5 vCPU)."
  }

  assert {
    condition     = aws_lb.this.idle_timeout == 130
    error_message = "O ALB precisa de 130 s de idle timeout para o streaming da Lia."
  }

  assert {
    condition     = length(aws_ssm_parameter.llm_api_key) == 1 && length(aws_ssm_parameter.openai_api_key) == 0
    error_message = "Só as chaves informadas viram parâmetros."
  }

  assert {
    condition     = strcontains(aws_ecs_task_definition.backend.container_definitions, "ARGOS_LLM_PRIMARY_MODEL")
    error_message = "O modelo principal deve chegar ao backend."
  }

  assert {
    condition     = !strcontains(aws_ecs_task_definition.backend.container_definitions, "gsk_teste")
    error_message = "Chaves de API não podem aparecer em texto na task definition."
  }

  assert {
    condition     = aws_db_instance.this.publicly_accessible == false && aws_db_instance.this.engine_version == "15"
    error_message = "O banco deve ser privado e PostgreSQL 15."
  }

  assert {
    condition     = length(aws_budgets_budget.monthly) == 0
    error_message = "Sem e-mail não há orçamento."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.this) == 1 && output.app_url == "https://d111111abcdef8.cloudfront.net"
    error_message = "Sem domínio, o console deve ficar em HTTPS pelo CloudFront."
  }

  assert {
    condition     = strcontains(aws_ecs_task_definition.backend.container_definitions, "https://d111111abcdef8.cloudfront.net")
    error_message = "O backend deve conhecer o endereço HTTPS (links de e-mail e notificações)."
  }

  assert {
    condition     = alltrue([for rule in aws_security_group.alb.ingress : length(coalesce(rule.cidr_blocks, [])) == 0 && length(coalesce(rule.prefix_list_ids, [])) == 1])
    error_message = "Com CloudFront, o ALB só aceita o CloudFront (nada de 0.0.0.0/0)."
  }

  assert {
    condition = alltrue([for behavior in aws_cloudfront_distribution.this[0].ordered_cache_behavior :
    behavior.compress == false && behavior.viewer_protocol_policy == "https-only" if behavior.path_pattern == "/api/*"])
    error_message = "A API passa sem compressão (streaming da Lia) e só por HTTPS."
  }

  assert {
    condition     = toset(aws_cloudfront_cache_policy.api[0].parameters_in_cache_key_and_forwarded_to_origin[0].headers_config[0].headers[0].items) == toset(["Authorization", "X-API-Key"])
    error_message = "Authorization e X-API-Key precisam chegar ao backend também em GET."
  }
}

run "com_dominio_chaves_e_email" {
  command = apply

  variables {
    domain_name     = "argos.exemplo.com.br"
    route53_zone_id = "Z0123456789ABCDEFGHIJ"
    alert_email     = "time@exemplo.com.br"
    llm_routes = {
      primary   = { base_url = "https://api.groq.com/openai/v1", model = "openai/gpt-oss-120b" }
      fallback1 = { base_url = "https://generativelanguage.googleapis.com/v1beta/openai/", model = "gemini-flash-lite-latest" }
      fallback2 = { provider = "anthropic", model = "claude-haiku-5-5" }
    }
    llm_api_keys   = { primary = "gsk_a", fallback1 = "AIza_b", fallback2 = "sk-ant-c" }
    openai_api_key = "sk-teste"
    cohere_api_key = "co-teste"
    smtp_host      = "email-smtp.us-east-1.amazonaws.com"
    smtp_username  = "AKIATESTE"
    smtp_password  = "senha"
  }

  assert {
    condition     = length(aws_lb_listener.https) == 1 && length(aws_route53_record.app) == 1
    error_message = "Com domínio deve haver HTTPS e o registro DNS."
  }

  assert {
    condition     = length(aws_ssm_parameter.llm_api_key) == 3 && length(aws_ssm_parameter.cohere_api_key) == 1
    error_message = "Cada chave informada vira um parâmetro SecureString."
  }

  assert {
    condition     = strcontains(aws_ecs_task_definition.backend.container_definitions, "ARGOS_LLM_FALLBACK2_PROVIDER")
    error_message = "Os fallbacks devem chegar ao backend."
  }

  assert {
    condition     = strcontains(aws_ecs_task_definition.backend.container_definitions, "SMTP_HOST")
    error_message = "Com SMTP configurado, o e-mail deve chegar ao backend."
  }

  assert {
    condition     = length(aws_budgets_budget.monthly) == 1
    error_message = "Com e-mail deve haver orçamento."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.this) == 0 && output.app_url == "https://argos.exemplo.com.br"
    error_message = "Com domínio próprio, o HTTPS fica no ALB e o CloudFront não é criado."
  }
}

run "slot_invalido_e_recusado" {
  command = plan

  variables {
    llm_routes = { principal = { model = "x" } }
  }

  expect_failures = [var.llm_routes]
}
