#!/bin/sh
# Ativa (ou desativa) uma feature flag de falha na OpenTelemetry Demo via API do flagd-ui.
# Nenhum alerta ou deploy falso e enviado: o proprio Argos detecta os erros reais nos spans OTLP recebidos
# (TelemetryMonitor) e abre/resolve o incidente automaticamente.
#   ./demo/trigger-failure.sh                               liga cartFailure (100%)
#   ./demo/trigger-failure.sh productCatalogFailure         liga productCatalogFailure
#   ./demo/trigger-failure.sh productCatalogFailure reset   desliga a falha
set -eu

FLAG="${1:-cartFailure}"
ACTION="${2:-on}"

case "$FLAG" in
  cartFailure)           VARIANT="100%"; SERVICE="cart" ;;
  productCatalogFailure) VARIANT="on";   SERVICE="product-catalog" ;;
  paymentFailure)        VARIANT="100%"; SERVICE="payment" ;;
  adFailure)             VARIANT="on";   SERVICE="ad" ;;
  *) echo "Falha desconhecida: $FLAG (cartFailure | productCatalogFailure | paymentFailure | adFailure)" >&2; exit 1 ;;
esac

if [ "$ACTION" = "reset" ]; then STATE="off"; else STATE="$VARIANT"; fi

current="$(curl -sf http://localhost:8080/feature/api/read)"
payload="$(printf '%s' "$current" | jq --arg f "$FLAG" --arg v "$STATE" '
  .flags[$f].defaultVariant = $v |
  if (.flags[$f].targeting.if // null) != null then .flags[$f].targeting.if[1] = $v else . end |
  {data: {"$schema": "https://flagd.dev/schema/v0/flags.json", flags: .flags}}
')"
curl -sf -X POST http://localhost:8080/feature/api/write -H 'Content-Type: application/json' -d "$payload" >/dev/null

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$ACTION" != "reset" ] && [ -f "$SCRIPT_DIR/generated/argos.env" ]; then
  . "$SCRIPT_DIR/generated/argos.env"
  if [ -n "${ARGOS_API_KEY:-}" ]; then
    curl -sf -X POST http://localhost:8081/api/webhooks/deploys \
      -H "X-API-Key: $ARGOS_API_KEY" -H 'Content-Type: application/json' \
      -d "{\"service\":\"$SERVICE\",\"version\":\"v1.4.2\",\"environment\":\"production\",\"commitSha\":\"a8f4c21e9b0d\",\"author\":\"ci-bot\",\"description\":\"Release automatica antes de $FLAG\"}" >/dev/null || true
    echo "Deploy v1.4.2 registrado no servico '$SERVICE' para correlacao DORA."
  fi
fi

echo "Feature flag '$FLAG' na OpenTelemetry Demo -> '$STATE'"
echo "O Argos monitora o fluxo OTLP em tempo real e abrira/resolvera o incidente automaticamente em alguns segundos."
echo "Acompanhe ao vivo em: http://localhost:3030"
