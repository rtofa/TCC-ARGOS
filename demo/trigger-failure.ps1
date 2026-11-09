# Injeta (ou remove) uma falha na aplicacao de demo via API do flagd-ui, registra um deploy suspeito e avisa o Argos.
#   .\demo\trigger-failure.ps1                                  liga cartFailure (100%) e abre o incidente
#   .\demo\trigger-failure.ps1 -Flag productCatalogFailure      escolhe outra falha
#   .\demo\trigger-failure.ps1 -Flag productCatalogFailure -Reset
param(
    [ValidateSet('cartFailure', 'productCatalogFailure', 'paymentFailure', 'adFailure')]
    [string]$Flag = 'cartFailure',
    [switch]$Reset
)
$ErrorActionPreference = 'Stop'

$Scenarios = @{
    cartFailure           = @{ On = '100%'; Service = 'cart';            Title = 'Taxa de erro alta no servico de carrinho (cart)' }
    productCatalogFailure = @{ On = 'on';   Service = 'product-catalog'; Title = 'Falhas ao consultar o catalogo de produtos (product-catalog)' }
    paymentFailure        = @{ On = '100%'; Service = 'payment';         Title = 'Pagamentos falhando no servico payment' }
    adFailure             = @{ On = 'on';   Service = 'ad';              Title = 'Falhas ao carregar anuncios no servico ad' }
}
$scenario = $Scenarios[$Flag]
$variant = if ($Reset) { 'off' } else { $scenario.On }

$EnvFile = Join-Path $PSScriptRoot 'generated\argos.env'
if (-not (Test-Path $EnvFile)) { throw "Arquivo $EnvFile nao encontrado. Suba o Argos primeiro." }
$vars = @{}
Get-Content $EnvFile | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $vars[$Matches[1]] = $Matches[2].Trim() } }

# Atualiza a feature flag pelo flagd-ui (atomico, recarrega o flagd sem reiniciar container).
$state = Invoke-RestMethod -Uri 'http://localhost:8080/feature/api/read'
$flagObj = $state.flags.$Flag
$flagObj.defaultVariant = $variant
if ($flagObj.PSObject.Properties['targeting'] -and $flagObj.targeting.if) {
    $flagObj.targeting.if[1] = $variant
}
$writeBody = @{ data = @{ '$schema' = 'https://flagd.dev/schema/v0/flags.json'; flags = $state.flags } } | ConvertTo-Json -Depth 20
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/feature/api/write' -ContentType 'application/json' -Body $writeBody | Out-Null

# Na abertura da falha, registra tambem um deploy recente do servico afetado para demonstrar a correlacao deploy <-> incidente.
if (-not $Reset) {
    $deployedAt = [DateTimeOffset]::UtcNow.AddMinutes(-5).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $deploy = @{
        service     = $scenario.Service
        version     = "3.1.0-$Flag"
        environment = 'production'
        commitSha   = '4c44a889f0e1d2c3'
        author      = 'ci-bot'
        url         = 'https://github.com/rtofa/TCC-ARGOS/actions'
        description = "Ativa alteracao associada ao cenario $Flag"
        deployedAt  = $deployedAt
    } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$($vars['ARGOS_URL'])/api/webhooks/deploys" `
        -Headers @{ 'X-API-Key' = $vars['ARGOS_API_KEY'] } -ContentType 'application/json' -Body $deploy | Out-Null
}

$alert = @{
    title       = $scenario.Title
    dedupKey    = "demo-$Flag"
    status      = if ($Reset) { 'resolved' } else { 'firing' }
    severity    = 'critical'
    service     = $scenario.Service
    description = "Falha controlada na feature flag '$Flag' (variante '$variant') na OpenTelemetry Demo."
} | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri "$($vars['ARGOS_URL'])/api/webhooks/alerts" `
    -Headers @{ 'X-API-Key' = $vars['ARGOS_API_KEY'] } -ContentType 'application/json' -Body $alert

$actions = ($result.results | ForEach-Object { $_.action }) -join ', '
Write-Host ("Flag {0}: {1}" -f $Flag, $variant)
Write-Host "Argos: alerta enviado -> $actions"
Write-Host 'Abra Incidentes no console do Argos: http://localhost:3030/incidents'
