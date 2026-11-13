<#
.SYNOPSIS
    Argos — Quality Gate de Deploy (Quadro 4, Seção 3.4.3)
.DESCRIPTION
    Consulta o endpoint GET /api/webhooks/deploys/quality-gate e bloqueia o deploy
    (exit code 1) caso exista incidente SEV1 aberto ou monitor em alerta crítico.
#>
param(
    [string]$Service = $env:ARGOS_SERVICE,
    [string]$ApiUrl  = $(if ($env:ARGOS_API_URL) { $env:ARGOS_API_URL } else { "http://localhost:8080/api" }),
    [string]$ApiKey  = $env:ARGOS_API_KEY
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Error "[Argos Quality Gate] Variavel ARGOS_API_KEY nao configurada."
    exit 1
}

$query = "strict=true"
if (-not [string]::IsNullOrWhiteSpace($Service)) {
    $query += "&service=$([uri]::EscapeDataString($Service))"
}

$url = "$($ApiUrl.TrimEnd('/'))/webhooks/deploys/quality-gate?$query"
Write-Host "[Argos Quality Gate] Consultando $url ..." -ForegroundColor Cyan

try {
    $resp = Invoke-RestMethod -Method Get -Uri $url -Headers @{ "X-API-Key" = $ApiKey; "Accept" = "application/json" }
    Write-Host ($resp | ConvertTo-Json -Depth 5)
    Write-Host "[Argos Quality Gate] APROVADO — $($resp.reason)" -ForegroundColor Green
    exit 0
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -eq 409) {
        Write-Host "[Argos Quality Gate] BLOQUEADO (HTTP 409) — Incidente SEV1 ou alerta critico em aberto!" -ForegroundColor Red
    } else {
        Write-Host "[Argos Quality Gate] Erro ao consultar o Argos (HTTP $statusCode): $($_.Exception.Message)" -ForegroundColor Red
    }
    exit 1
}
