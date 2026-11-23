# Testes com provedor simulado (não precisam de credenciais nem criam nada na AWS):
#   terraform test
# Conferem a confiança do papel (só a branch main do repositório) e o alcance da política (só os recursos do Argos).

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    }
  }

  mock_resource "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    }
  }

  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/argos-github-deploy"
    }
  }
}

run "papel_restrito_a_branch_main" {
  command = apply

  assert {
    condition = toset(jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"]) == toset([
      "repo:rtofa@132409828/TCC-ARGOS@1381994977:ref:refs/heads/main",
      "repo:rtofa/TCC-ARGOS:ref:refs/heads/main",
    ])
    error_message = "O papel deve aceitar só tokens da branch main do repositório (formato com IDs e formato antigo)."
  }

  assert {
    condition     = alltrue([for subject in jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"] : length(regexall("[*?]", subject)) == 0])
    error_message = "O sub não pode ter curingas: com StringEquals eles nem funcionariam, e abririam o papel a outros repositórios."
  }

  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "A audiência do token deve ser o STS."
  }

  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 1
    error_message = "Por padrão o provedor OIDC é criado."
  }
}

run "politica_so_alcanca_os_recursos_do_argos" {
  command = apply

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      statement.Resource == "*" ? contains(["EcrLogin", "FindAppUrl"], statement.Sid) : length(regexall("argos", statement.Resource)) > 0
    ])
    error_message = "Só o login no ECR e a leitura do ALB podem ter Resource = *; o resto deve ser restrito ao Argos."
  }

  assert {
    condition     = contains([for statement in jsondecode(aws_iam_role_policy.deploy.policy).Statement : statement.Resource], "arn:aws:ecs:us-east-2:123456789012:service/argos-prod/*")
    error_message = "O papel deve poder reiniciar só os serviços do cluster argos-prod."
  }
}

run "conta_com_provedor_oidc_existente" {
  command = apply

  variables {
    create_oidc_provider = false
  }

  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0
    error_message = "Com create_oidc_provider = false, o provedor existente é reutilizado."
  }
}
