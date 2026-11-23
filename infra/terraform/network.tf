# VPC em 2 zonas de disponibilidade.
# - Subnets públicas: ALB e tasks do ECS. As tasks recebem IP público para sair à internet (ECR, modelos de IA,
#   Slack/Discord) sem NAT Gateway, que custaria ~32 USD/mês. Os security groups só aceitam tráfego vindo do ALB.
# - Subnets privadas: RDS, sem rota para a internet.
# Produção real: tasks em subnets privadas com NAT Gateway (veja docs/PRODUCAO_AWS_ECS.md).

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "${var.project}-${var.environment}"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = local.name }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = local.name }
}

resource "aws_subnet" "public" {
  count                   = length(local.azs)
  vpc_id                  = aws_vpc.this.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-public-${local.azs[count.index]}" }
}

resource "aws_subnet" "private" {
  count             = length(local.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  tags              = { Name = "${local.name}-private-${local.azs[count.index]}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${local.name}-public" }

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = "${local.name}-private" }
}

resource "aws_route_table_association" "private" {
  count          = length(aws_subnet.private)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# ---------------------------------------------------------------------------------------------------------------
# Security groups: internet -> ALB -> tasks -> RDS
# ---------------------------------------------------------------------------------------------------------------
resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "ALB: HTTP/HTTPS da internet"
  vpc_id      = aws_vpc.this.id

  # Com CloudFront, o ALB só aceita os servidores de origem do CloudFront: o endereço HTTP direto deixa de responder
  # e todo acesso passa pelo HTTPS. Sem CloudFront (domínio próprio ou desligado), aceita a internet.
  dynamic "ingress" {
    for_each = local.use_cloudfront ? [] : [80, 443]
    content {
      description = ingress.value == 80 ? "HTTP" : "HTTPS"
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
    }
  }

  dynamic "ingress" {
    for_each = local.use_cloudfront ? [80] : []
    content {
      description     = "HTTP vindo do CloudFront"
      from_port       = ingress.value
      to_port         = ingress.value
      protocol        = "tcp"
      prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront[0].id]
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "frontend" {
  name        = "${local.name}-frontend"
  description = "Frontend (nginx): so o ALB entra"
  vpc_id      = aws_vpc.this.id

  ingress {
    description     = "HTTP do ALB"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "backend" {
  name        = "${local.name}-backend"
  description = "Backend (Spring Boot): so o ALB entra; sai para RDS, ECR e APIs externas"
  vpc_id      = aws_vpc.this.id

  ingress {
    description     = "API do ALB"
    from_port       = 8081
    to_port         = 8081
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "rds" {
  name        = "${local.name}-rds"
  description = "PostgreSQL: so o backend entra"
  vpc_id      = aws_vpc.this.id

  ingress {
    description     = "PostgreSQL do backend"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.backend.id]
  }
}
