# Acesso do GitHub Actions à AWS para o deploy automático (job "deploy" de .github/workflows/ci.yml).
#
# Sem chave guardada no GitHub: a cada execução o GitHub emite um token OIDC de curta duração, e a AWS o troca por
# credenciais temporárias deste papel, só se o token vier do repositório e da branch configurados.
# O papel só publica imagens nos repositórios do Argos no ECR e reinicia os serviços do cluster do Argos.

data "aws_caller_identity" "current" {}

locals {
  github_oidc_url = "https://token.actions.githubusercontent.com"
  account_id      = data.aws_caller_identity.current.account_id
  cluster         = "${var.project}-${var.environment}"

  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn

  # "sub" aceito: o formato com IDs imutáveis (o que o GitHub envia hoje, visto no CloudTrail) e o formato antigo.
  allowed_subjects = compact([
    var.github_repository_with_ids == "" ? "" : "repo:${var.github_repository_with_ids}:ref:refs/heads/${var.deploy_branch}",
    "repo:${var.github_repository}:ref:refs/heads/${var.deploy_branch}",
  ])
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_oidc_provider ? 1 : 0
  url            = local.github_oidc_url
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1
  url   = local.github_oidc_url
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.project}-github-deploy"
  description          = "Deploy do Argos pelo GitHub Actions (${var.github_repository}, branch ${var.deploy_branch})"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRoleWithWebIdentity"
      Principal = { Federated = local.oidc_provider_arn }
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          # Pull requests e outras branches não assumem o papel, mesmo do mesmo repositório.
          "token.actions.githubusercontent.com:sub" = local.allowed_subjects
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "deploy" {
  name = "deploy"
  role = aws_iam_role.deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:DescribeRepositories",
          "ecr:GetDownloadUrlForLayer",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
        ]
        Resource = "arn:aws:ecr:${var.region}:${local.account_id}:repository/${var.project}/*"
      },
      {
        Sid      = "EcsCluster"
        Effect   = "Allow"
        Action   = "ecs:DescribeClusters"
        Resource = "arn:aws:ecs:${var.region}:${local.account_id}:cluster/${local.cluster}"
      },
      {
        Sid      = "EcsDeploy"
        Effect   = "Allow"
        Action   = ["ecs:DescribeServices", "ecs:UpdateService"]
        Resource = "arn:aws:ecs:${var.region}:${local.account_id}:service/${local.cluster}/*"
      },
      {
        # Só para mostrar o endereço do console no resumo do workflow: o HTTPS do CloudFront ou, sem ele, o ALB
        # (essas leituras não aceitam restrição por recurso).
        Sid      = "FindAppUrl"
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:DescribeLoadBalancers", "cloudfront:ListDistributions"]
        Resource = "*"
      },
    ]
  })
}
