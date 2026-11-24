<#
.SYNOPSIS
  Liga, desliga ou confere a OpenTelemetry Demo que roda no EC2 da AWS, via SSM (sem SSH nem porta aberta).

.DESCRIPTION
  Usa o comando "Run Command" do AWS Systems Manager para executar, dentro do EC2 da demo, o demo/trigger-failure.sh
  (feature flags de falha do flagd) ou uma checagem de status. O Argos detecta os erros reais nos spans e abre o
  incidente sozinho em alguns segundos. Lê o ID do EC2 e a região das saídas do Terraform (infra/terraform).

.EXAMPLE
  .\infra\scripts\demo-failure.ps1 -Status
  .\infra\scripts\demo-failure.ps1 -Flag caos            # várias falhas reais ao mesmo tempo e mais tráfego
  .\infra\scripts\demo-failure.ps1 -Flag caos -Reset
  .\infra\scripts\demo-failure.ps1 -Flag productCatalogFailure
  .\infra\scripts\demo-failure.ps1 -Flag productCatalogFailure -Reset
#>
param(
    [ValidateSet("caos", "cartFailure", "productCatalogFailure", "paymentFailure", "adFailure")]
    [string]$Flag = "productCatalogFailure",
    [switch]$Reset,
    [switch]$Status
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$terraformDir = Join-Path $root "infra\terraform"

# aws e terraform escrevem avisos no stderr; no PowerShell 5.1 isso viraria erro fatal. Vale o código de saída.
function Invoke-Native([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Command } finally { $ErrorActionPreference = $previous }
}

function Get-TerraformOutput([string]$Name) {
    return (Invoke-Native { & terraform "-chdir=$terraformDir" output -raw $Name 2>$null } | Out-String).Trim()
}

$instance = Get-TerraformOutput "demo_instance_id"
$region = Get-TerraformOutput "region"
if (-not $instance) {
    throw "EC2 da demo não encontrado (terraform output demo_instance_id vazio). Ela precisa de demo_argos_api_key no terraform.tfvars."
}

$compose = "docker compose --env-file .env --env-file .env.override -f compose.yaml -f /opt/argos/demo/compose.argos.yaml"
if ($Status) {
    $commands = @(
        "echo '== inicialização'; tail -n 3 /var/log/argos-demo-init.log",
        "echo '== containers'; cd /opt/argos/demo/opentelemetry-demo && $compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}'",
        "echo '== memória (MB)'; free -m | head -2",
        "echo '== envio ao Argos (erros do Collector nos últimos 5 min)'; docker logs --since 5m otel-collector 2>&1 | grep -iE 'otlphttp/argos.*(error|fail)|status code 40' | tail -n 5 || true"
    )
} else {
    $action = if ($Reset) { "reset" } else { "on" }
    $commands = @("sh /opt/argos/demo/trigger-failure.sh $Flag $action")
}

# Parâmetros por arquivo: passar JSON na linha de comando do PowerShell 5.1 quebra as aspas.
$parameters = New-TemporaryFile
[IO.File]::WriteAllText($parameters.FullName, (@{ commands = $commands } | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding $false))
try {
    $commandId = (Invoke-Native {
        aws ssm send-command --region $region --instance-ids $instance --document-name AWS-RunShellScript `
            --parameters "file://$($parameters.FullName)" --query Command.CommandId --output text
    } | Out-String).Trim()
    if (-not $commandId) { throw "O SSM não aceitou o comando (o EC2 já terminou de ligar?)." }
    Invoke-Native { aws ssm wait command-executed --region $region --command-id $commandId --instance-id $instance 2>$null } | Out-Null
    $result = Invoke-Native {
        aws ssm get-command-invocation --region $region --command-id $commandId --instance-id $instance --output json
    } | Out-String | ConvertFrom-Json
} finally {
    Remove-Item $parameters.FullName -ErrorAction SilentlyContinue
}

Write-Host $result.StandardOutputContent
if ($result.StandardErrorContent) { Write-Host $result.StandardErrorContent -ForegroundColor Yellow }
if ($result.Status -ne "Success") { throw "Comando terminou com status $($result.Status)." }
