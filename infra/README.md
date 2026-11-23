# Argos na AWS: ECS Fargate com Terraform

Esta pasta cria e publica o Argos na AWS com dois comandos repetíveis: `terraform apply`, que cria a infraestrutura, e `deploy.ps1`, que publica uma versão. Os padrões são econômicos, pensados para uma conta free tier. A arquitetura de produção completa e a revisão do código estão em [docs/PRODUCAO_AWS_ECS.md](../docs/PRODUCAO_AWS_ECS.md).

## O que é criado

```
Internet ──► ALB (HTTP, ou HTTPS com domínio) ──┬── /api/*  ──► ECS Fargate: backend (Spring Boot, porta 8081)
                                                └── resto   ──► ECS Fargate: frontend (nginx com o SPA)
backend ──► RDS PostgreSQL 15 + pgvector (subnet privada, sem acesso pela internet)
backend ──► Groq / OpenAI / Cohere / Slack / Discord (saída pela internet)
```

| Recurso | Configuração padrão |
|---|---|
| VPC | 2 zonas; subnets públicas (ALB e tasks) e privadas (RDS); **sem NAT Gateway** para economizar |
| ALB | Idle timeout de 130 s (streaming da Lia); `/api/*` → backend, resto → frontend |
| ECS Fargate | Backend 0,5 vCPU / 1 GB (1 task); frontend 0,25 vCPU / 0,5 GB (1 task); deploy com rollback automático |
| RDS | PostgreSQL 15, `db.t4g.micro`, 20 GB gp3, criptografado, TLS obrigatório, backup de 1 dia |
| Segredos | SSM Parameter Store (SecureString): senha do banco, chave do JWT (gerada pelo Terraform), chaves de IA e SMTP |
| ECR | Repositórios `argos/backend` e `argos/frontend` (scan on push, mantém 10 imagens) |
| CloudWatch | Logs dos dois serviços (14 dias) |
| Orçamento | Alerta por e-mail em 80% do gasto real e 100% do previsto (se `alert_email` for informado) |
| Domínio (opcional) | Certificado ACM + registro no Route 53 + HTTPS |

As tasks ficam em subnets públicas com IP público para sair à internet sem NAT Gateway (cerca de 32 USD/mês). Os security groups só aceitam conexões vindas do ALB, e o banco fica em subnet privada.

## Custos (estimativa)

Valores aproximados em us-east-1, ligado 24 horas por dia, **sem** descontos de free tier:

| Item | USD/mês (aprox.) |
|---|---|
| Fargate: backend (0,5 vCPU / 1 GB) | 18 |
| Fargate: frontend (0,25 vCPU / 0,5 GB) | 9 |
| Application Load Balancer | 16 a 20 |
| RDS `db.t4g.micro` + 20 GB | 14 |
| IPs públicos IPv4 (ALB e tasks) | 10 a 15 |
| CloudWatch, ECR, SSM | menos de 2 |
| **Total** | **cerca de 70** |

- O free tier ou os créditos de conta nova cobrem parte disso. As regras dependem da data de criação da conta: contas criadas a partir de julho de 2025 recebem créditos em vez dos 12 meses grátis. Confira em **Billing > Free Tier** no console da AWS.
- Os tokens dos modelos de IA (Groq, OpenAI, Cohere) são cobrados pelos próprios provedores.
- Para não gastar quando não estiver usando, veja "Desligar o ambiente" no fim.

## Checklist: o que você precisa ter

### Conta e acesso
- [ ] Conta AWS ativa, com **MFA no usuário root**.
- [ ] Um usuário para o Terraform, sem usar o root. Duas opções:
  - **IAM Identity Center (recomendado):** usuário com o permission set `AdministratorAccess`, configurado com `aws configure sso`.
  - **Usuário IAM:** com a política `AdministratorAccess` e uma access key, configurado com `aws configure`.
- [ ] Credenciais configuradas **só na sua máquina**. Nunca cole access keys em chats, no código ou no Git.

### Ferramentas no Windows
- [ ] AWS CLI v2: `winget install Amazon.AWSCLI`
- [ ] Terraform 1.6 ou mais novo: `winget install Hashicorp.Terraform`
- [ ] Docker Desktop (já instalado) e Git.
- [ ] Teste: `aws sts get-caller-identity` mostra a sua conta.

### Chaves e dados da configuração
- [ ] **Chave nova do Groq.** Revogue a usada no desenvolvimento, que foi exposta em conversa.
- [ ] Chave de um segundo provedor para o fallback, por exemplo Gemini no Google AI Studio (tem plano gratuito).
- [ ] Chave da OpenAI para os embeddings da busca vetorial (opcional; sem ela a Lia usa só busca textual).
- [ ] Chave da Cohere para o rerank (opcional).
- [ ] E-mail para os alertas de custo.
- [ ] Região: `us-east-1` (mais barata) ou `sa-east-1` (São Paulo, menor latência).
- [ ] Acesso de administrador ao repositório no GitHub, para criar a variável `AWS_DEPLOY_ROLE_ARN` do deploy automático.
- [ ] Opcional: domínio em uma hosted zone do Route 53, para ter HTTPS.
- [ ] Opcional: Amazon SES com domínio verificado e credenciais SMTP, para o canal de e-mail.

## Primeira instalação

Todos os comandos rodam no PowerShell, a partir da raiz do repositório.

1. **Configure a configuração** do Terraform:

   ```powershell
   cd infra\terraform
   Copy-Item terraform.tfvars.example terraform.tfvars
   notepad terraform.tfvars   # preencha região, e-mail, modelos e chaves
   terraform init
   ```

2. **Crie os repositórios de imagem** e envie as imagens. Os serviços do ECS precisam que as imagens já existam:

   ```powershell
   terraform apply -target="aws_ecr_repository.backend" -target="aws_ecr_repository.frontend"
   cd ..\..
   .\infra\scripts\deploy.ps1 -PushOnly
   ```

3. **Crie o resto da infraestrutura.** Leva de 10 a 15 minutos, por causa do RDS:

   ```powershell
   cd infra\terraform
   terraform apply
   terraform output app_url
   ```

4. **Aguarde o backend ficar saudável.** Na primeira subida ele cria as tabelas, a extensão pgvector e indexa os 22 documentos da Lia, o que leva de 2 a 5 minutos. Para acompanhar:

   ```powershell
   aws logs tail /ecs/argos-prod/backend --follow --region us-east-1
   ```

5. **Primeiro acesso:** abra o `app_url`, clique em **Primeira vez aqui? Criar conta** e crie a organização. Quem cria vira Administrador.

## Publicar uma nova versão

- **Automático:** depois de configurado (próxima seção), todo merge na `main` publica sozinho.
- **Manual**, da sua máquina, com o código na `main`:

  ```powershell
  .\infra\scripts\deploy.ps1
  ```

  O script faz o build das duas imagens (tags com o commit e `latest`), envia ao ECR, força um novo deploy no ECS e espera os serviços estabilizarem. Se a versão nova não ficar saudável, o ECS volta sozinho para a anterior (circuit breaker).

## Deploy automático a cada merge na main

O job **Deploy na AWS** do workflow de CI ([.github/workflows/ci.yml](../.github/workflows/ci.yml)) faz o mesmo que o `deploy.ps1`, no GitHub:

| Situação | O que acontece |
|---|---|
| Pull request aberto ou atualizado | Só os testes (backend, frontend e segurança). Nada é publicado. |
| Merge na `main` | Testes → Quality Gate do Argos → build das imagens (com cache) → push para o ECR → novo deploy no ECS → espera ficar saudável. Se a versão nova não subir, o ECS volta para a anterior e o job fica vermelho. |
| Ambiente desligado | O job termina verde com o aviso "Deploy pulado". Não publica nem gasta nada na AWS. |
| Ambiente sem os serviços (meio da primeira instalação) | O job só envia as imagens. |

**Configuração (uma vez só, vale para todos os ciclos de liga/desliga):**

1. Crie o acesso do GitHub à AWS. É uma stack separada, que o `terraform destroy` do ambiente não apaga, e não tem custo:

   ```powershell
   cd infra\github-deploy
   terraform init
   terraform apply
   terraform output role_arn
   ```

   Ela cria o provedor OIDC do GitHub e o papel `argos-github-deploy`. O papel só aceita workflows da branch `main` deste repositório, só envia imagens aos repositórios do Argos e só reinicia os serviços dele. Nenhuma chave fica guardada no GitHub: a cada execução, o GitHub troca um token de curta duração por credenciais temporárias. Se o repositório mudar de dono ou de nome, ajuste `github_repository` em `variables.tf`.
2. No GitHub, abra **Settings > Secrets and variables > Actions > Variables > New repository variable** e crie `AWS_DEPLOY_ROLE_ARN` com o valor do passo 1. Se a região não for `us-east-1`, crie também `AWS_REGION`.
3. Para publicar sem esperar um merge, use **Actions > CI > Run workflow**, na `main`.

Na primeira instalação, o workflow também substitui o `deploy.ps1 -PushOnly` (passo 2): depois do `terraform apply -target=...` do ECR, rode **Run workflow**. Ele envia as imagens sem build na sua máquina. Depois, rode o `terraform apply` completo.

## Mudar configuração (modelos, chaves, capacidade)

Edite `terraform.tfvars` e rode `terraform apply`. Mudanças nas variáveis do backend geram uma nova revisão da task definition, e o ECS faz o deploy sozinho.

## Logs e depuração

- Logs do backend, em JSON (formato ECS) com `traceId`: `aws logs tail /ecs/argos-prod/backend --follow`
- Logs do frontend: `aws logs tail /ecs/argos-prod/frontend --follow`
- Estado dos serviços: `aws ecs describe-services --cluster argos-prod --services backend frontend`
- Entrar no container do backend (ECS Exec, requer o Session Manager plugin da AWS CLI): `aws ecs execute-command --cluster argos-prod --task <id-da-task> --container backend --interactive --command "sh"`
- Saúde da aplicação: `<app_url>/actuator/health/readiness` deve responder `{"status":"UP"}`.

## Checklist de verificação depois do deploy

- [ ] **No ensaio:** `python infra\scripts\smoke-test.py <app_url>` termina com todas as verificações passando (cadastro, login, incidentes, chaves de API, OTLP, alertas, deploys, KPIs, Lia e isolamento entre organizações). Ele cria duas organizações de teste; não rode no ambiente da apresentação.
- [ ] `<app_url>` abre a tela de entrada; `<app_url>/actuator/health/readiness` responde UP.
- [ ] Criar organização, entrar e criar um usuário Editor.
- [ ] Gerar uma chave de API e enviar o sinal de teste pelo **Conectar App** (o serviço aparece na Telemetria).
- [ ] Abrir um incidente manual e mudar o status.
- [ ] Conversar com a Lia: o botão de informações (Administrador) mostra 22 documentos e busca "Híbrida (textual + vetorial)" se a chave da OpenAI foi informada.
- [ ] Fazer um segundo deploy com `deploy.ps1` e conferir que você **continua logado**: a chave do JWT é compartilhada.
- [ ] Conferir o alerta de orçamento no e-mail (a AWS pede confirmação na primeira vez).

## Ligar só no dia da apresentação

O ambiente não precisa ficar ligado. Ligado só no dia, o custo é de uns 0,10 USD por hora.

1. **Ensaio (alguns dias antes):** faça a primeira instalação completa, teste tudo e desligue com o snapshot (passo 4).
2. **No dia, cerca de 1 hora antes:** em `terraform.tfvars`, coloque `db_snapshot_identifier` com o nome do snapshot
   guardado e repita "Primeira instalação" (passos 2 a 4). Os dados do ensaio voltam junto: organização, usuários,
   incidentes, KPIs e chaves de API.
3. **Durante a apresentação:** nada a fazer. Uma correção mergeada na `main` é publicada sozinha (ou use `deploy.ps1`).
4. **Depois da apresentação:** com `db_skip_final_snapshot = false` no `terraform.tfvars`, rode
   `terraform output db_final_snapshot_name` (anote o nome) e `terraform destroy`. O snapshot fica guardado
   (custa centavos por mês) e todo o resto é apagado.

Para listar os snapshots guardados: `aws rds describe-db-snapshots --query "DBSnapshots[].DBSnapshotIdentifier"`.

## Desligar o ambiente

- **Pausar a aplicação** (o ALB e o RDS continuam cobrando): em `terraform.tfvars`, coloque `backend_desired_count = 0` e `frontend_desired_count = 0` e rode `terraform apply`.
- **Pausar o banco** por até 7 dias: `aws rds stop-db-instance --db-instance-identifier argos-prod`.
- **Apagar tudo:** `terraform destroy`. O banco só vira snapshot se `db_skip_final_snapshot = false`; sem isso, os dados são apagados.

## Cuidados

- `terraform.tfstate` e `terraform.tfvars` contêm segredos (senha do banco, chave do JWT, chaves de IA). Os dois estão no `.gitignore`: não compartilhe nem commite. Para trabalhar em equipe, mova o estado para um bucket S3 (veja o comentário em `versions.tf`).
- Para produção real, ajuste a capacidade e a proteção do banco (comentários no fim de `terraform.tfvars.example`) e resolva os itens pendentes de [docs/PRODUCAO_AWS_ECS.md](../docs/PRODUCAO_AWS_ECS.md): retenção da telemetria, trava na indexação da Lia e WAF.

## Testes da infraestrutura

A configuração tem testes com provedores simulados, que não precisam de credenciais nem criam nada:

```powershell
cd infra\terraform
terraform init
terraform test
```
