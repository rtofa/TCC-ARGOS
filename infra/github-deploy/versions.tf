terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Estado local (terraform.tfstate, fora do Git), separado do ambiente em infra/terraform: o destroy do ambiente
  # não apaga este acesso, e o deploy automático continua configurado para a próxima vez que o ambiente subir.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
      Purpose   = "github-deploy"
    }
  }
}
