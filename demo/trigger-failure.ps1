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
$variant = if ($Reset) { 'off' } else { $Variants[$Flag] }

$state = Invoke-RestMethod -Uri 'http://localhost:8080/feature/api/read'
$flagObj = $state.flags.$Flag
$flagObj.defaultVariant = $variant
if ($flagObj.PSObject.Properties['targeting'] -and $flagObj.targeting.if) {
    $flagObj.targeting.if[1] = $variant
}
$writeBody = @{ data = @{ '$schema' = 'https://flagd.dev/schema/v0/flags.json'; flags = $state.flags } } | ConvertTo-Json -Depth 20
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/feature/api/write' -ContentType 'application/json' -Body $writeBody | Out-Null

Write-Host ("Feature flag '{0}' na OpenTelemetry Demo -> '{1}'" -f $Flag, $variant)
Write-Host 'O Argos monitora o fluxo OTLP em tempo real e abrira/resolvera o incidente automaticamente em alguns segundos.'
Write-Host 'Acompanhe ao vivo em: http://localhost:3030'
