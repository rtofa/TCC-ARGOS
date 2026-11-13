#!/usr/bin/env bash
# ==============================================================================
# Argos — Suíte de Benchmark e Coleta Experimental (Quadro 3, Seção 3.3.2)
# Executa ArgosBenchmarkIT e exporta os relatórios CSV + Markdown em docs/benchmarks/
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}/backend"

echo "================================================================="
echo " Argos — Suíte de Benchmark e Coleta Experimental (Quadro 3)"
echo "================================================================="
./mvnw -B -ntp -Dtest="ArgosBenchmarkIT" test

echo ""
echo "[OK] Relatórios exportados em ${REPO_ROOT}/docs/benchmarks/:"
ls -lh "${REPO_ROOT}/docs/benchmarks/"
