<#
.SYNOPSIS
    Executa a suíte experimental do Quadro 3 (Seção 3.3.2) e gera os relatórios CSV + Markdown em docs/benchmarks/.
#>
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $repoRoot "backend")
try {
    Write-Host "=================================================================" -ForegroundColor Cyan
    Write-Host " Argos — Suíte de Benchmark e Coleta Experimental (Quadro 3)" -ForegroundColor Cyan
    Write-Host "=================================================================" -ForegroundColor Cyan
    .\mvnw.cmd -B -ntp -Dtest="ArgosBenchmarkIT" test
    Write-Host "`n[OK] Relatórios exportados em docs/benchmarks/:" -ForegroundColor Green
    Get-ChildItem (Join-Path $repoRoot "docs\benchmarks") | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
} finally {
    Pop-Location
}
