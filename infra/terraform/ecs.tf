# ECS Fargate: um serviço para o backend (Spring Boot) e outro para o frontend (nginx com o SPA).

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.name}/backend"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "frontend" {
  name              = "/ecs/${local.name}/frontend"
  retention_in_days = var.log_retention_days
}

resource "aws_ecs_cluster" "this" {
  name = local.name

  # Container Insights tem custo; ligue quando precisar de métricas por container.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

locals {
  backend_environment = concat(
    [
      { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/argos?sslmode=require" },
      { name = "SPRING_DATASOURCE_USERNAME", value = aws_db_instance.this.username },
      { name = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", value = "10" },
      { name = "SPRING_JPA_SHOW_SQL", value = "false" },
      { name = "LOGGING_STRUCTURED_FORMAT_CONSOLE", value = "ecs" },
      { name = "ARGOS_CONSOLE_URL", value = local.app_url },
      { name = "ARGOS_MAIL_FROM", value = var.mail_from },
    ],
    flatten([
      for slot, route in var.llm_routes : [
        { name = "${local.llm_env_prefix[slot]}_PROVIDER", value = route.provider },
        { name = "${local.llm_env_prefix[slot]}_BASE_URL", value = route.base_url },
        { name = "${local.llm_env_prefix[slot]}_MODEL", value = route.model },
        { name = "${local.llm_env_prefix[slot]}_REASONING_EFFORT", value = route.reasoning_effort },
      ] if contains(local.llm_slots, slot)
    ]),
    local.has_smtp ? [
      { name = "SMTP_HOST", value = var.smtp_host },
      { name = "SMTP_PORT", value = "587" },
      { name = "SMTP_AUTH", value = "true" },
      { name = "SMTP_STARTTLS", value = "true" },
    ] : [],
  )

  backend_secrets = concat(
    [
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn },
      { name = "ARGOS_JWT_PRIVATE_KEY", valueFrom = aws_ssm_parameter.jwt_private_key.arn },
      { name = "ARGOS_JWT_PUBLIC_KEY", valueFrom = aws_ssm_parameter.jwt_public_key.arn },
    ],
    [for slot in local.llm_slots : { name = "${local.llm_env_prefix[slot]}_API_KEY", valueFrom = aws_ssm_parameter.llm_api_key[slot].arn }],
    local.has_openai ? [{ name = "OPENAI_API_KEY", valueFrom = aws_ssm_parameter.openai_api_key[0].arn }] : [],
    local.has_cohere ? [{ name = "COHERE_API_KEY", valueFrom = aws_ssm_parameter.cohere_api_key[0].arn }] : [],
    local.has_smtp ? [
      { name = "SMTP_USER", valueFrom = aws_ssm_parameter.smtp_username[0].arn },
      { name = "SMTP_PASS", valueFrom = aws_ssm_parameter.smtp_password[0].arn },
    ] : [],
  )
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${local.name}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = "backend"
    image        = "${aws_ecr_repository.backend.repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8081, protocol = "tcp" }]
    environment  = local.backend_environment
    secrets      = local.backend_secrets
    stopTimeout  = 35
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.backend.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "backend"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "frontend" {
  family                   = "${local.name}-frontend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.frontend_cpu
  memory                   = var.frontend_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = "frontend"
    image        = "${aws_ecr_repository.frontend.repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 80, protocol = "tcp" }]
    # O ALB já roteia /api/* ao backend; o proxy /api do nginx aponta para o próprio ALB como reserva.
    environment = [{ name = "API_UPSTREAM", value = "http://${aws_lb.this.dns_name}" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.frontend.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "frontend"
      }
    }
  }])
}

resource "aws_ecs_service" "backend" {
  name                   = "backend"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.backend.arn
  desired_count          = var.backend_desired_count
  launch_type            = "FARGATE"
  enable_execute_command = true

  # Spring Boot + Flyway + indexação da Lia em 0,5 vCPU podem levar alguns minutos na primeira subida.
  health_check_grace_period_seconds  = 300
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.backend.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8081
  }

  depends_on = [aws_lb_listener_rule.api, aws_iam_role_policy.execution_secrets]
}

resource "aws_ecs_service" "frontend" {
  name                   = "frontend"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.frontend.arn
  desired_count          = var.frontend_desired_count
  launch_type            = "FARGATE"
  enable_execute_command = true

  health_check_grace_period_seconds  = 60
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.frontend.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "frontend"
    container_port   = 80
  }

  depends_on = [aws_lb_listener.http]
}
