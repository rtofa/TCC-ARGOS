# ---------------------------------------------------------------------------------------------------------------
# OpenTelemetry Demo (Astronomy Shop) rodando na AWS (EC2 + Docker Compose) e enviando OTLP para o Argos.
# ---------------------------------------------------------------------------------------------------------------
data "aws_ssm_parameter" "al2023_ami" {
  count = var.enable_demo ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_security_group" "demo" {
  count       = var.enable_demo ? 1 : 0
  name        = "${local.name}-demo"
  description = "OpenTelemetry Demo: porta 8080 (loja/flagd/loadgen) e saida para o ALB do Argos"
  vpc_id      = aws_vpc.this.id

  ingress {
    description = "Astronomy Shop HTTP (8080)"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = var.demo_allowed_cidrs
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
  count = var.enable_demo ? 1 : 0
  name  = "${local.name}-demo-ec2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "demo_ssm" {
  count      = var.enable_demo ? 1 : 0
  role       = aws_iam_role.demo[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "demo" {
  count = var.enable_demo ? 1 : 0
  name  = "${local.name}-demo"
  role  = aws_iam_role.demo[0].name
}

resource "aws_instance" "demo" {
  count                       = var.enable_demo ? 1 : 0
  ami                         = data.aws_ssm_parameter.al2023_ami[0].value
  instance_type               = var.demo_instance_type
  subnet_id                   = aws_subnet.public[0].id
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.demo[0].id]
  iam_instance_profile        = aws_iam_instance_profile.demo[0].name

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }

  user_data = <<-EOF
    #!/bin/bash
    set -euxo pipefail
    exec > >(tee /var/log/argos-demo-init.log | logger -t argos-demo) 2>&1

    # Swap de 2 GB para folga de memoria com os ~20 containers da OpenTelemetry Demo
    if [ ! -f /swapfile ]; then
      fallocate -l 2G /swapfile
      chmod 600 /swapfile
      mkswap /swapfile
      swapon /swapfile
      echo '/swapfile none swap sw 0 0' >> /etc/fstab
    fi

    dnf install -y docker git jq
    systemctl enable --now docker

    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -fsSL "https://github.com/docker/compose/releases/download/v2.32.4/docker-compose-linux-x86_64" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

    rm -rf /opt/argos
    git clone --depth 1 --branch "${var.demo_repo_branch}" "${var.demo_repo_url}" /opt/argos

    ARGOS_URL="${local.app_url}"
    DEMO_EMAIL="${var.demo_admin_email}"
    DEMO_PASSWORD="${var.demo_admin_password}"

    echo "Aguardando Argos responder em $ARGOS_URL/actuator/health/readiness ..."
    for i in $(seq 1 120); do
      code=$(curl -s -o /dev/null -w '%%{http_code}' "$ARGOS_URL/actuator/health/readiness" || true)
      if [ "$code" = "200" ]; then
        break
      fi
      sleep 5
    done

    register_body=$(jq -n --arg e "$DEMO_EMAIL" --arg p "$DEMO_PASSWORD" \
      '{organizationName: "Loja Demo", userName: "Admin Demo", email: $e, password: $p}')
    token=$(curl -s -X POST "$ARGOS_URL/api/auth/register" -H 'Content-Type: application/json' \
      -d "$register_body" | jq -r '.token // empty')

    if [ -z "$token" ]; then
      login_body=$(jq -n --arg e "$DEMO_EMAIL" --arg p "$DEMO_PASSWORD" '{email: $e, password: $p}')
      token=$(curl -s -X POST "$ARGOS_URL/api/auth/login" -H 'Content-Type: application/json' \
        -d "$login_body" | jq -r '.token // empty')
    fi

    key=$(curl -s -X POST "$ARGOS_URL/api/api-keys" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -d '{"name":"OpenTelemetry Collector (AWS demo)"}' | jq -r '.key // empty')

    mkdir -p /opt/argos/demo/generated
    printf 'ARGOS_API_KEY=%s\nARGOS_URL=%s\n' "$key" "$ARGOS_URL" > /opt/argos/demo/generated/argos.env

    git clone --quiet --depth 1 --branch 3.1.0 \
      https://github.com/open-telemetry/opentelemetry-demo.git /opt/argos/demo/opentelemetry-demo

    sed -e "s|__ARGOS_API_KEY__|$key|g" \
        -e "s|http://host.docker.internal:8081/api/webhooks/otlp|$ARGOS_URL/api/webhooks/otlp|g" \
        /opt/argos/demo/otelcol-config-extras.yml.template \
      > /opt/argos/demo/opentelemetry-demo/src/otel-collector/otelcol-config-extras.yml

    cd /opt/argos/demo/opentelemetry-demo
    docker compose --env-file .env --env-file .env.override \
      -f compose.yaml -f /opt/argos/demo/compose.argos.yaml up -d --remove-orphans
  EOF

  depends_on = [aws_lb_listener.http]

  tags = { Name = "${local.name}-demo" }
}
