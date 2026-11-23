output "role_arn" {
  description = "Valor da variável AWS_DEPLOY_ROLE_ARN no GitHub (Settings > Secrets and variables > Actions > Variables)."
  value       = aws_iam_role.deploy.arn
}

output "region" {
  description = "Valor da variável AWS_REGION no GitHub (opcional; o padrão do workflow é us-east-2)."
  value       = var.region
}
