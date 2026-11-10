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
  cartFailure)           VARIANT="100%" ;;
  productCatalogFailure) VARIANT="on"   ;;
  paymentFailure)        VARIANT="100%" ;;
  adFailure)             VARIANT="on"   ;;
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

echo "Feature flag '$FLAG' na OpenTelemetry Demo -> '$STATE'"
echo "O Argos monitora o fluxo OTLP em tempo real e abrira/resolvera o incidente automaticamente em alguns segundos."
echo "Acompanhe ao vivo em: http://localhost:3030"
