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

variable "github_repository_with_ids" {
  description = <<-EOT
    O mesmo repositório no formato com IDs imutáveis que o GitHub usa no "sub" do token OIDC:
    dono@<id do dono>/repo@<id do repo>. Os IDs impedem que uma conta ou repositório recriado com o mesmo nome
    assuma o papel. Para descobrir: o principalId do evento AssumeRoleWithWebIdentity no CloudTrail, ou
    https://api.github.com/repos/<dono>/<repo> (campos owner.id e id). Vazio = só o formato antigo.
  EOT
  type        = string
  default     = "rtofa@132409828/TCC-ARGOS@1381994977"

  validation {
    condition     = var.github_repository_with_ids == "" || can(regex("^[A-Za-z0-9_.-]+@[0-9]+/[A-Za-z0-9_.-]+@[0-9]+$", var.github_repository_with_ids))
    error_message = "Use o formato dono@<id>/repo@<id>, por exemplo rtofa@132409828/TCC-ARGOS@1381994977."
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
