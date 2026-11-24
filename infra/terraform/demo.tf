# Dados de demonstração: a OpenTelemetry Demo (Astronomy Shop, ~20 microsserviços instrumentados com OpenTelemetry)
# num EC2, mandando traces, logs e métricas ao Argos pelo HTTPS, com uma chave de API da sua organização.
# Baseado na proposta do rtofa (commit a056e32), com estas mudanças:
# - os dados vão para a organização da chave informada (a sua), em vez de criar uma "Loja Demo" com senha pública;
# - a chave fica no SSM e o EC2 a lê ao ligar, então não aparece no user_data;
# - nenhuma porta aberta para a internet: as falhas são ligadas por SSM (infra/scripts/demo-failure.ps1);
# - c7i-flex.large (4 GB): a demo 3.1.0 já vem sem Jaeger/Grafana/Prometheus/OpenSearch (o Argos faz esse papel) e
#   cabe em 4 GB. Contas no plano gratuito só aceitam tipos elegíveis ao free tier (t3.medium é recusado).
# Só é criada com demo_argos_api_key preenchida. O destroy do ambiente apaga o EC2 junto; a chave continua válida no
# snapshot do banco, então no dia da apresentação basta religar.

locals {
  # Só "a chave foi preenchida?" deixa de ser sensível (o count não aceita valores sensíveis); a chave continua.
  use_demo = var.enable_demo && nonsensitive(var.demo_argos_api_key != "")

  # Script de inicialização: endereço do Argos e nome do parâmetro no SSM, nunca a chave. O replace garante LF mesmo
  # que o checkout no Windows tenha trocado as quebras de linha (bash no EC2 quebra com \r).
  demo_user_data = local.use_demo ? replace(templatefile("${path.module}/demo-init.sh.tftpl", {
    region         = var.region
    key_parameter  = aws_ssm_parameter.demo_argos_api_key[0].name
    argos_url      = local.app_url
    repo_url       = var.demo_repo_url
    repo_branch    = var.demo_repo_branch
    otel_demo_tag  = "3.1.0"
    compose_plugin = "https://github.com/docker/compose/releases/download/v2.32.4/docker-compose-linux-x86_64"
  }), "\r\n", "\n") : ""
}

data "aws_ssm_parameter" "al2023_ami" {
  count = local.use_demo ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_ssm_parameter" "demo_argos_api_key" {
  count = local.use_demo ? 1 : 0
  name  = "${local.ssm_prefix}/demo/argos-api-key"
  type  = "SecureString"
  value = var.demo_argos_api_key
}

resource "aws_security_group" "demo" {
  count       = local.use_demo ? 1 : 0
  name        = "${local.name}-demo"
  description = "OpenTelemetry Demo: so saida (imagens, GitHub e Argos)"
  vpc_id      = aws_vpc.this.id

  # Loja e feature flags (porta 8080) só para os IPs de demo_allowed_cidrs; por padrão, nenhum.
  dynamic "ingress" {
    for_each = length(var.demo_allowed_cidrs) > 0 ? [1] : []
    content {
      description = "Astronomy Shop (8080)"
      from_port   = 8080
      to_port     = 8080
      protocol    = "tcp"
      cidr_blocks = var.demo_allowed_cidrs
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name}-demo" }
}

resource "aws_iam_role" "demo" {
  count = local.use_demo ? 1 : 0
  name  = "${local.name}-demo-ec2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

# Session Manager e Run Command: acesso ao EC2 sem SSH nem porta aberta (ligar falhas, ver logs).
resource "aws_iam_role_policy_attachment" "demo_ssm" {
  count      = local.use_demo ? 1 : 0
  role       = aws_iam_role.demo[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "demo_key" {
  count = local.use_demo ? 1 : 0
  name  = "read-argos-api-key"
  role  = aws_iam_role.demo[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "ssm:GetParameter"
      Resource = aws_ssm_parameter.demo_argos_api_key[0].arn
    }]
  })
}

resource "aws_iam_instance_profile" "demo" {
  count = local.use_demo ? 1 : 0
  name  = "${local.name}-demo"
  role  = aws_iam_role.demo[0].name
}

resource "aws_instance" "demo" {
  count                       = local.use_demo ? 1 : 0
  ami                         = data.aws_ssm_parameter.al2023_ami[0].value
  instance_type               = var.demo_instance_type
  subnet_id                   = aws_subnet.public[0].id
  associate_public_ip_address = true # sem NAT: precisa de IP público para baixar as imagens
  vpc_security_group_ids      = [aws_security_group.demo[0].id]
  iam_instance_profile        = aws_iam_instance_profile.demo[0].name

  metadata_options {
    http_tokens = "required" # IMDSv2
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    encrypted   = true
  }

  user_data_replace_on_change = true
  user_data                   = local.demo_user_data

  # Uma AMI nova não deve recriar a demo no meio do uso; o próximo ciclo de liga/desliga já pega a mais recente.
  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${local.name}-demo" }

  depends_on = [aws_iam_role_policy.demo_key, aws_iam_role_policy_attachment.demo_ssm]
}
