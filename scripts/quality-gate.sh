#!/usr/bin/env bash
# ==============================================================================
# Argos — Quality Gate de Deploy (Quadro 4, Seção 3.4.3)
# Consulta o endpoint GET /api/webhooks/deploys/quality-gate e bloqueia o deploy
# (exit code 1) caso exista incidente SEV1 aberto ou monitor em alerta crítico.
# ==============================================================================
set -euo pipefail

ARGOS_API_URL="${ARGOS_API_URL:-http://localhost:8080/api}"
ARGOS_API_KEY="${ARGOS_API_KEY:-}"
SERVICE="${1:-${ARGOS_SERVICE:-}}"

if [[ -z "${ARGOS_API_KEY}" ]]; then
  echo "[Argos Quality Gate] Variável ARGOS_API_KEY não configurada." >&2
  exit 1
fi

QUERY="strict=true"
if [[ -n "${SERVICE}" ]]; then
  QUERY="${QUERY}&service=${SERVICE}"
fi

URL="${ARGOS_API_URL%/}/webhooks/deploys/quality-gate?${QUERY}"
echo "[Argos Quality Gate] Consultando ${URL} ..."

HTTP_STATUS=$(curl -s -o /tmp/argos-qg.json -w "%{http_code}" \
  -H "X-API-Key: ${ARGOS_API_KEY}" \
  -H "Accept: application/json" \
  "${URL}")

cat /tmp/argos-qg.json || true
echo ""

if [[ "${HTTP_STATUS}" == "200" ]]; then
  echo "[Argos Quality Gate] APROVADO (HTTP 200) — Deploy autorizado."
  exit 0
elif [[ "${HTTP_STATUS}" == "409" ]]; then
  echo "[Argos Quality Gate] BLOQUEADO (HTTP 409) — Incidente SEV1 ou alerta crítico em aberto!" >&2
  exit 1
else
  echo "[Argos Quality Gate] Falha inesperada ao consultar o Argos (HTTP ${HTTP_STATUS})." >&2
  exit 1
fi
