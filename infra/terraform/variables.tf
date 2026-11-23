# ---------------------------------------------------------------------------------------------------------------
# Geral
# ---------------------------------------------------------------------------------------------------------------
variable "project" {
  description = "Prefixo dos recursos."
  type        = string
  default     = "argos"
}

variable "environment" {
  description = "Nome do ambiente (prod, staging...)."
  type        = string
  default     = "prod"
}

variable "region" {
  description = "Região da AWS. us-east-2 (Ohio): a única liberada pela organização da conta (SCP), com o mesmo preço de us-east-1."
  type        = string
  default     = "us-east-2"
}

variable "vpc_cidr" {
  description = "Faixa de IPs da VPC."
  type        = string
  default     = "10.40.0.0/16"
}

# ---------------------------------------------------------------------------------------------------------------
# Domínio (opcional). Sem domínio, o Argos fica em http://<dns-do-alb>.
# ---------------------------------------------------------------------------------------------------------------
variable "domain_name" {
  description = "Domínio do console (ex.: argos.seudominio.com.br). Vazio = sem HTTPS, acesso pelo DNS do ALB."
  type        = string
  default     = ""
}

variable "route53_zone_id" {
  description = "ID da hosted zone do Route 53 que contém o domínio (obrigatório se domain_name for preenchido)."
  type        = string
  default     = ""
}

variable "enable_cloudfront" {
  description = <<-EOT
    Sem domínio próprio, coloca o CloudFront na frente do ALB para ter HTTPS (certificado *.cloudfront.net, sem o
    aviso "Não seguro"), e o ALB passa a aceitar só o CloudFront. Ignorado quando domain_name é preenchido.
  EOT
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------------------------------------------
# Capacidade (padrões econômicos para conta free tier; aumente para produção real)
# ---------------------------------------------------------------------------------------------------------------
variable "backend_cpu" {
  description = "CPU da task do backend (1024 = 1 vCPU)."
  type        = number
  default     = 512
}

variable "backend_memory" {
  description = "Memória da task do backend em MB."
  type        = number
  default     = 1024
}

variable "backend_desired_count" {
  description = "Quantidade de tasks do backend. Produção: 2 ou mais."
  type        = number
  default     = 1
}

variable "frontend_cpu" {
  description = "CPU da task do frontend."
  type        = number
  default     = 256
}

variable "frontend_memory" {
  description = "Memória da task do frontend em MB."
  type        = number
  default     = 512
}

variable "frontend_desired_count" {
  description = "Quantidade de tasks do frontend."
  type        = number
  default     = 1
}

variable "image_tag" {
  description = "Tag das imagens no ECR usadas pelas task definitions."
  type        = string
  default     = "latest"
}

# ---------------------------------------------------------------------------------------------------------------
# Banco (RDS PostgreSQL 15 + pgvector)
# ---------------------------------------------------------------------------------------------------------------
variable "db_instance_class" {
  description = "Classe da instância. db.t4g.micro é elegível ao free tier."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Disco do banco em GB (free tier: até 20)."
  type        = number
  default     = 20
}

variable "db_max_allocated_storage" {
  description = "Limite do crescimento automático do disco em GB (0 desliga)."
  type        = number
  default     = 50
}

variable "db_multi_az" {
  description = "Banco em duas zonas (dobra o custo). Produção real: true."
  type        = bool
  default     = false
}

variable "db_backup_retention_days" {
  description = "Dias de backup automático. Contas free tier aceitam no máximo 1."
  type        = number
  default     = 1
}

variable "db_deletion_protection" {
  description = "Impede apagar o banco por engano. Produção real: true."
  type        = bool
  default     = false
}

variable "db_skip_final_snapshot" {
  description = "Não tirar snapshot ao destruir. Use false para guardar os dados ao desligar o ambiente."
  type        = bool
  default     = true
}

variable "db_snapshot_identifier" {
  description = "Restaura o banco a partir deste snapshot (ex.: o snapshot final da última vez que o ambiente foi desligado)."
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------------------------------------------
# Lia: modelos de linguagem (principal + até 3 fallbacks), embeddings e rerank
# ---------------------------------------------------------------------------------------------------------------
variable "llm_routes" {
  description = <<-EOT
    Modelos da Lia por slot (primary, fallback1, fallback2, fallback3). provider: "openai" (qualquer API compatível
    com OpenAI: OpenAI, Groq, Gemini, OpenRouter...) ou "anthropic". base_url vazio = endereço padrão do provedor.
    reasoning_effort só é enviado quando preenchido (low, medium, high; alguns modelos não aceitam).
  EOT
  type = map(object({
    provider         = optional(string, "openai")
    base_url         = optional(string, "")
    model            = string
    reasoning_effort = optional(string, "")
  }))
  default = {}

  validation {
    condition     = alltrue([for slot in keys(var.llm_routes) : contains(["primary", "fallback1", "fallback2", "fallback3"], slot)])
    error_message = "Os slots válidos são: primary, fallback1, fallback2 e fallback3."
  }
}

variable "llm_api_keys" {
  description = "Chave de API de cada slot de llm_routes (mesmos nomes de slot). Vão para o SSM Parameter Store."
  type        = map(string)
  default     = {}
  sensitive   = true
}

variable "openai_api_key" {
  description = "Chave da OpenAI para embeddings (busca vetorial). Vazia = Lia só com busca textual."
  type        = string
  default     = ""
  sensitive   = true
}

variable "cohere_api_key" {
  description = "Chave da Cohere para rerank. Vazia = sem rerank."
  type        = string
  default     = ""
  sensitive   = true
}

# ---------------------------------------------------------------------------------------------------------------
# E-mail (opcional, Amazon SES via SMTP)
# ---------------------------------------------------------------------------------------------------------------
variable "smtp_host" {
  description = "Servidor SMTP (ex.: email-smtp.us-east-1.amazonaws.com). Vazio = canal de e-mail desligado."
  type        = string
  default     = ""
}

variable "smtp_username" {
  description = "Usuário SMTP (credencial SMTP do SES)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "smtp_password" {
  description = "Senha SMTP (credencial SMTP do SES)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "mail_from" {
  description = "Remetente das notificações por e-mail (domínio verificado no SES)."
  type        = string
  default     = "noreply@argos.local"
}

# ---------------------------------------------------------------------------------------------------------------
# Custos
# ---------------------------------------------------------------------------------------------------------------
variable "alert_email" {
  description = "E-mail que recebe os alertas de orçamento. Vazio = sem orçamento."
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  description = "Orçamento mensal em USD; avisa em 80% do gasto real e em 100% do previsto."
  type        = number
  default     = 15
}

variable "log_retention_days" {
  description = "Dias de retenção dos logs no CloudWatch."
  type        = number
  default     = 14
}

variable "ecr_force_delete" {
  description = "Permite destruir os repositórios do ECR mesmo com imagens (útil para desmontar o ambiente)."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------------------------------------------
# OpenTelemetry Demo (Astronomy Shop) na AWS
# ---------------------------------------------------------------------------------------------------------------
variable "enable_demo" {
  description = "Sobe uma instância EC2 na AWS rodando a OpenTelemetry Demo enviando telemetria ao Argos."
  type        = bool
  default     = true
}

variable "demo_instance_type" {
  description = "Tipo da instância EC2 para os ~20 containers da OpenTelemetry Demo (recomendado: t3.medium, 4 GB RAM)."
  type        = string
  default     = "t3.medium"
}

variable "demo_allowed_cidrs" {
  description = "CIDRs permitidos para acessar a loja/loadgen/flagd da demo na porta 8080."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "demo_repo_url" {
  description = "URL do repositório Git clonado pela EC2 da demo."
  type        = string
  default     = "https://github.com/rtofa/TCC-ARGOS.git"
}

variable "demo_repo_branch" {
  description = "Branch do repositório clonado pela EC2 da demo."
  type        = string
  default     = "main"
}

variable "demo_admin_email" {
  description = "E-mail do administrador da organização Loja Demo criada automaticamente na AWS."
  type        = string
  default     = "admin@demo.argos"
}

variable "demo_admin_password" {
  description = "Senha do administrador da organização Loja Demo na AWS."
  type        = string
  default     = "Argos@2026!"
  sensitive   = true
}
