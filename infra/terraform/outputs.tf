output "app_url" {
  description = "Endereço do console do Argos."
  value       = local.app_url
}

output "alb_dns_name" {
  description = "DNS do load balancer (aponte o domínio para ele, se usar um registrador fora do Route 53)."
  value       = aws_lb.this.dns_name
}

output "region" {
  value = var.region
}

output "ecr_backend_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "ecr_frontend_url" {
  value = aws_ecr_repository.frontend.repository_url
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "backend_service_name" {
  value = aws_ecs_service.backend.name
}

output "frontend_service_name" {
  value = aws_ecs_service.frontend.name
}

output "rds_endpoint" {
  description = "Endereço do PostgreSQL (acessível só de dentro da VPC)."
  value       = aws_db_instance.this.address
}

output "backend_log_group" {
  value = aws_cloudwatch_log_group.backend.name
}

output "webhooks_base_url" {
  description = "Base dos endereços de integração (OTLP, alertas, deploys) com o cabeçalho X-API-Key."
  value       = "${local.app_url}/api/webhooks"
}

output "db_final_snapshot_name" {
  description = "Nome do snapshot que o destroy vai criar (use em db_snapshot_identifier na próxima vez que ligar)."
  value       = var.db_skip_final_snapshot ? "(desligado: db_skip_final_snapshot = true)" : aws_db_instance.this.final_snapshot_identifier
}
