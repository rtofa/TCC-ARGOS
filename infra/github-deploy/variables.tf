variable "region" {
  description = "Região do ambiente (a mesma de infra/terraform)."
  type        = string
  default     = "us-east-2"
}

variable "project" {
  description = "Prefixo dos recursos (o mesmo de infra/terraform)."
  type        = string
  default     = "argos"
}

variable "environment" {
  description = "Ambiente (o mesmo de infra/terraform); o cluster se chama <project>-<environment>."
  type        = string
  default     = "prod"
}

variable "github_repository" {
  description = "Repositório que pode publicar, no formato dono/nome."
  type        = string
  default     = "rtofa/TCC-ARGOS"

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "Use o formato dono/nome, por exemplo rtofa/TCC-ARGOS."
  }
}

variable "deploy_branch" {
  description = "Única branch cujos workflows podem assumir o papel de deploy."
  type        = string
  default     = "main"
}

variable "create_oidc_provider" {
  description = "Cria o provedor OIDC do GitHub no IAM. Use false se a conta já tiver um (só pode existir um por conta)."
  type        = bool
  default     = true
}
