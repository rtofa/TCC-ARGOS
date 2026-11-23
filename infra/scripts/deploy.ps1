<#
.SYNOPSIS
  Publica uma versão do Argos na AWS: build das imagens, push para o ECR e novo deploy no ECS.

.DESCRIPTION
  Lê os nomes dos recursos das saídas do Terraform (infra/terraform), então rode depois do terraform apply.
  Cada imagem recebe duas tags: o commit atual (rastreabilidade) e "latest" (usada pelas task definitions).

.PARAMETER PushOnly
  Só envia as imagens, sem acionar o ECS. Use na primeira instalação, antes de criar os serviços.

.EXAMPLE
  .\infra\scripts\deploy.ps1
  .\infra\scripts\deploy.ps1 -PushOnly
#>
param(
    [switch]$PushOnly,
    [string]$Tag = ""
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$terraformDir = Join-Path $root "infra\terraform"

# docker, aws e terraform escrevem progresso e avisos no stderr. No PowerShell 5.1, com a saída redirecionada
# (log, CI), isso viraria erro fatal por causa do "Stop" acima; quem decide se o passo falhou é o código de saída.
function Invoke-Native([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Command
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-Checked([string]$Description, [scriptblock]$Command) {
    Write-Host "==> $Description" -ForegroundColor Cyan
    Invoke-Native $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Falhou: $Description (código $LASTEXITCODE)"
    }
}

function Get-TerraformOutput([string]$Name) {
    $value = Invoke-Native { & terraform "-chdir=$terraformDir" output -raw $Name 2>$null }
    if ($LASTEXITCODE -ne 0 -or -not $value) {
        throw "Saída '$Name' do Terraform não encontrada. Rode 'terraform apply' em infra/terraform antes."
    }
    return ($value | Out-String).Trim()
}

foreach ($tool in @("aws", "docker", "terraform", "git")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Ferramenta '$tool' não encontrada no PATH. Veja infra/README.md."
    }
}

if (-not $Tag) {
    $Tag = (& git -C $root rev-parse --short HEAD).Trim()
}

$backendRepo = Get-TerraformOutput "ecr_backend_url"
$frontendRepo = Get-TerraformOutput "ecr_frontend_url"
$registry = $backendRepo.Split("/")[0]
# Região tirada do endereço do ECR (<conta>.dkr.ecr.<região>.amazonaws.com): depois do apply com -target da
# primeira instalação, o Terraform ainda não gravou a saída "region".
$region = $registry.Split(".")[3]

# Pipe pelo cmd: o pipeline do PowerShell 5.1 trata a senha como texto e a altera (o ECR responde 400 Bad Request).
Invoke-Checked "Login no ECR ($registry)" {
    cmd /c "aws ecr get-login-password --region $region | docker login --username AWS --password-stdin $registry"
}

Invoke-Checked "Build do backend ($Tag)" {
    docker build --platform linux/amd64 -t "${backendRepo}:$Tag" -t "${backendRepo}:latest" (Join-Path $root "backend")
}
Invoke-Checked "Push do backend" { docker push "${backendRepo}:$Tag" }
Invoke-Checked "Push do backend (latest)" { docker push "${backendRepo}:latest" }

Invoke-Checked "Build do frontend ($Tag)" {
    docker build --platform linux/amd64 -t "${frontendRepo}:$Tag" -t "${frontendRepo}:latest" (Join-Path $root "frontend")
}
Invoke-Checked "Push do frontend" { docker push "${frontendRepo}:$Tag" }
Invoke-Checked "Push do frontend (latest)" { docker push "${frontendRepo}:latest" }

if ($PushOnly) {
    Write-Host "Imagens enviadas. Agora rode 'terraform apply' em infra/terraform para criar os serviços." -ForegroundColor Green
    exit 0
}

$cluster = Get-TerraformOutput "ecs_cluster_name"
$backendService = Get-TerraformOutput "backend_service_name"
$frontendService = Get-TerraformOutput "frontend_service_name"

Invoke-Checked "Novo deploy do backend" {
    aws ecs update-service --cluster $cluster --service $backendService --force-new-deployment --region $region --output text --query "service.serviceName"
}
Invoke-Checked "Novo deploy do frontend" {
    aws ecs update-service --cluster $cluster --service $frontendService --force-new-deployment --region $region --output text --query "service.serviceName"
}
Invoke-Checked "Aguardando os serviços estabilizarem (alguns minutos)" {
    aws ecs wait services-stable --cluster $cluster --services $backendService $frontendService --region $region
}

$url = Get-TerraformOutput "app_url"
Write-Host "Pronto! Argos $Tag no ar em $url" -ForegroundColor Green
