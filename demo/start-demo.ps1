# Sobe a aplicacao de microsservicos (OpenTelemetry Demo / Astronomy Shop) enviando telemetria ao Argos.
# Pre-requisito: o Argos no ar (docker compose up --build), que gera demo/generated/argos.env.
#   .\demo\start-demo.ps1          sobe a demo
#   .\demo\start-demo.ps1 -Stop    derruba a demo
param([switch]$Stop)
$ErrorActionPreference = 'Stop'

$DemoTag = '3.1.0'
$Here = $PSScriptRoot
$DemoDir = Join-Path $Here 'opentelemetry-demo'
$EnvFile = Join-Path $Here 'generated\argos.env'

function Invoke-DemoCompose {
    Push-Location $DemoDir
    try {
        docker compose --env-file .env --env-file .env.override -f compose.yaml -f (Join-Path $Here 'compose.argos.yaml') @args
        if ($LASTEXITCODE -ne 0) { throw "docker compose falhou ($LASTEXITCODE)" }
    } finally { Pop-Location }
}

if ($Stop) { Invoke-DemoCompose down; return }

if (-not (Test-Path $EnvFile)) {
    throw "Chave do Argos nao encontrada ($EnvFile). Rode 'docker compose up --build' na raiz do projeto primeiro."
}
$key = ((Get-Content $EnvFile | Where-Object { $_ -like 'ARGOS_API_KEY=*' }) -replace '^ARGOS_API_KEY=', '').Trim()
if (-not $key) { throw "ARGOS_API_KEY vazia em $EnvFile" }

if (-not (Test-Path $DemoDir)) {
    Write-Host "Baixando a OpenTelemetry Demo $DemoTag ..."
    git clone --quiet --depth 1 --branch $DemoTag https://github.com/open-telemetry/opentelemetry-demo.git $DemoDir
    if ($LASTEXITCODE -ne 0) { throw 'git clone falhou' }
}

$template = Get-Content (Join-Path $Here 'otelcol-config-extras.yml.template') -Raw
$target = Join-Path $DemoDir 'src\otel-collector\otelcol-config-extras.yml'
[IO.File]::WriteAllText($target, $template.Replace('__ARGOS_API_KEY__', $key), (New-Object Text.UTF8Encoding $false))

Write-Host 'Subindo a aplicacao de demonstracao (a primeira vez baixa varias imagens)...'
Invoke-DemoCompose up -d --remove-orphans

Write-Host ''
Write-Host 'Loja (gera telemetria):       http://localhost:8080'
Write-Host 'Gerador de carga:             http://localhost:8080/loadgen/'
Write-Host 'Feature flags (falhas):       http://localhost:8080/feature'
Write-Host 'Console do Argos:             http://localhost:3030'
Write-Host 'Injetar uma falha:            .\demo\trigger-failure.ps1   (use -Reset para voltar ao normal)'
