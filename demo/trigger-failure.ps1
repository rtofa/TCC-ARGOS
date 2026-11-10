# Ativa (ou desativa) uma feature flag de falha na OpenTelemetry Demo via API do flagd-ui.
# Nenhum alerta ou deploy falso e enviado: o proprio Argos detecta os erros reais nos spans OTLP recebidos
# (TelemetryMonitor) e abre/resolve o incidente automaticamente.
#   .\demo\trigger-failure.ps1                                  liga cartFailure (100%)
#   .\demo\trigger-failure.ps1 -Flag productCatalogFailure      liga productCatalogFailure
#   .\demo\trigger-failure.ps1 -Flag productCatalogFailure -Reset
param(
    [ValidateSet('cartFailure', 'productCatalogFailure', 'paymentFailure', 'adFailure')]
    [string]$Flag = 'cartFailure',
    [switch]$Reset
)
$ErrorActionPreference = 'Stop'

$Variants = @{
    cartFailure           = '100%'
    productCatalogFailure = 'on'
    paymentFailure        = '100%'
    adFailure             = 'on'
}
$Services = @{
    cartFailure           = 'cart'
    productCatalogFailure = 'product-catalog'
    paymentFailure        = 'payment'
    adFailure             = 'ad'
}
$variant = if ($Reset) { 'off' } else { $Variants[$Flag] }

$state = Invoke-RestMethod -Uri 'http://localhost:8080/feature/api/read'
$flagObj = $state.flags.$Flag
$flagObj.defaultVariant = $variant
if ($flagObj.PSObject.Properties['targeting'] -and $flagObj.targeting.if) {
    $flagObj.targeting.if[1] = $variant
}
$writeBody = @{ data = @{ '$schema' = 'https://flagd.dev/schema/v0/flags.json'; flags = $state.flags } } | ConvertTo-Json -Depth 20
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/feature/api/write' -ContentType 'application/json' -Body $writeBody | Out-Null

if (-not $Reset -and (Test-Path "$PSScriptRoot\generated\argos.env")) {
    $envLines = Get-Content "$PSScriptRoot\generated\argos.env"
    $apiKey = ($envLines | Where-Object { $_ -like 'ARGOS_API_KEY=*' }) -replace '^ARGOS_API_KEY=', ''
    if ($apiKey) {
        $nowUtc = (Get-Date).ToUniversalTime()
        $deployBody = @{
            service     = $Services[$Flag]
            version     = 'v1.4.2'
            environment = 'production'
            commitSha   = 'a8f4c21e9b0d'
            author      = 'ci-bot'
            description = "Release automatica antes de $Flag"
            committedAt = $nowUtc.AddMinutes(-12).ToString('o')
            deployedAt  = $nowUtc.ToString('o')
        } | ConvertTo-Json
        try {
            Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/webhooks/deploys' `
                -Headers @{ 'X-API-Key' = $apiKey } -ContentType 'application/json' -Body $deployBody | Out-Null
            Write-Host ("Deploy v1.4.2 registrado no servico '{0}' para correlacao DORA." -f $Services[$Flag])
        } catch {}
    }
}

Write-Host ("Feature flag '{0}' na OpenTelemetry Demo -> '{1}'" -f $Flag, $variant)
Write-Host 'O Argos monitora o fluxo OTLP em tempo real e abrira/resolvera o incidente automaticamente em alguns segundos.'
Write-Host 'Acompanhe ao vivo em: http://localhost:3030'
