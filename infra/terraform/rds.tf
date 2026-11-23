# PostgreSQL 15 gerenciado. A extensão pgvector é criada pela própria aplicação na primeira subida (o usuário
# mestre do RDS tem permissão para CREATE EXTENSION vector). O Flyway cria as tabelas.
# Liga/desliga: com db_skip_final_snapshot = false, o destroy guarda um snapshot; db_snapshot_identifier o restaura.

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "this" {
  name   = "${local.name}-pg15"
  family = "postgres15"

  # Conexões só com TLS (o backend usa sslmode=require).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # Registra consultas acima de 500 ms.
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
}

# Sufixo novo a cada criação do banco, para o snapshot final de cada ciclo liga/desliga ter nome único.
resource "random_id" "final_snapshot" {
  byte_length = 3
}

resource "aws_db_instance" "this" {
  identifier          = local.name
  snapshot_identifier = var.db_snapshot_identifier != "" ? var.db_snapshot_identifier : null
  engine              = "postgres"
  engine_version      = "15"
  instance_class      = var.db_instance_class

  db_name  = "argos"
  username = "argos"
  password = random_password.db.result
  port     = 5432

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage > var.db_allocated_storage ? var.db_max_allocated_storage : null
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = var.db_multi_az

  backup_retention_period    = var.db_backup_retention_days
  auto_minor_version_upgrade = true
  deletion_protection        = var.db_deletion_protection
  skip_final_snapshot        = var.db_skip_final_snapshot
  final_snapshot_identifier  = var.db_skip_final_snapshot ? null : "${local.name}-final-${random_id.final_snapshot.hex}"
  apply_immediately          = true
}
