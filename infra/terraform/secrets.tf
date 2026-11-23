# Segredos no SSM Parameter Store (SecureString, gratuito no nível padrão), injetados nas tasks pelo ECS.
# Nada sensível vai para variáveis de ambiente em texto nem para o repositório.

locals {
  ssm_prefix = "/${var.project}/${var.environment}"

  # Só os slots com rota e chave configuradas.
  llm_slots = toset([for slot in nonsensitive(keys(var.llm_api_keys)) : slot if contains(keys(var.llm_routes), slot)])

  # Nome da variável de ambiente que a aplicação lê para cada slot.
  llm_env_prefix = {
    primary   = "ARGOS_LLM_PRIMARY"
    fallback1 = "ARGOS_LLM_FALLBACK1"
    fallback2 = "ARGOS_LLM_FALLBACK2"
    fallback3 = "ARGOS_LLM_FALLBACK3"
  }

  has_openai = nonsensitive(var.openai_api_key != "")
  has_cohere = nonsensitive(var.cohere_api_key != "")
  has_smtp   = var.smtp_host != ""
}

resource "aws_ssm_parameter" "db_password" {
  name  = "${local.ssm_prefix}/db/password"
  type  = "SecureString"
  value = random_password.db.result
}

# Par RSA que assina as sessões (JWT): o mesmo em todas as tasks, então ninguém é deslogado em deploys.
resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_ssm_parameter" "jwt_private_key" {
  name  = "${local.ssm_prefix}/jwt/private-key"
  type  = "SecureString"
  value = tls_private_key.jwt.private_key_pem_pkcs8
}

resource "aws_ssm_parameter" "jwt_public_key" {
  name  = "${local.ssm_prefix}/jwt/public-key"
  type  = "SecureString"
  value = tls_private_key.jwt.public_key_pem
}

resource "aws_ssm_parameter" "llm_api_key" {
  for_each = local.llm_slots
  name     = "${local.ssm_prefix}/llm/${each.key}/api-key"
  type     = "SecureString"
  value    = var.llm_api_keys[each.key]
}

resource "aws_ssm_parameter" "openai_api_key" {
  count = local.has_openai ? 1 : 0
  name  = "${local.ssm_prefix}/openai/api-key"
  type  = "SecureString"
  value = var.openai_api_key
}

resource "aws_ssm_parameter" "cohere_api_key" {
  count = local.has_cohere ? 1 : 0
  name  = "${local.ssm_prefix}/cohere/api-key"
  type  = "SecureString"
  value = var.cohere_api_key
}

resource "aws_ssm_parameter" "smtp_username" {
  count = local.has_smtp ? 1 : 0
  name  = "${local.ssm_prefix}/smtp/username"
  type  = "SecureString"
  value = var.smtp_username
}

resource "aws_ssm_parameter" "smtp_password" {
  count = local.has_smtp ? 1 : 0
  name  = "${local.ssm_prefix}/smtp/password"
  type  = "SecureString"
  value = var.smtp_password
}
