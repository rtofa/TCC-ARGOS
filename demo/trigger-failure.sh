#!/bin/sh
# Ativa (ou desativa) feature flags de falha na OpenTelemetry Demo via API do flagd-ui.
# Nenhum alerta falso e enviado: o proprio Argos detecta os erros reais nos spans OTLP recebidos
# (TelemetryMonitor) e abre/resolve o incidente automaticamente.
#   ./demo/trigger-failure.sh                               liga cartFailure (100%)
#   ./demo/trigger-failure.sh productCatalogFailure         liga productCatalogFailure
#   ./demo/trigger-failure.sh productCatalogFailure reset   desliga a falha
#   ./demo/trigger-failure.sh caos                          varias falhas reais ao mesmo tempo e mais trafego
#   ./demo/trigger-failure.sh caos reset                    volta tudo ao normal
set -eu

FLAG="${1:-cartFailure}"
ACTION="${2:-on}"

# Modo caos: falhas reais em quase todos os servicos da demo enxuta (sem Kafka nem IA), em 50% das chamadas para a
# tela misturar erros e sucessos. Sem o vazamento de memoria do email, que poderia derrubar o servidor.
CAOS_FLAGS='{"cartFailure":"50%","paymentFailure":"50%","productCatalogFailure":"on","recommendationCacheFailure":"on","adFailure":"on","productCatalogLockContention":"on","intlShippingSlowdown":"5sec"}'

case "$FLAG" in
  caos)                  VARIANT="";     SERVICE="checkout" ;;
  cartFailure)           VARIANT="100%"; SERVICE="cart" ;;
  productCatalogFailure) VARIANT="on";   SERVICE="product-catalog" ;;
  paymentFailure)        VARIANT="100%"; SERVICE="payment" ;;
  adFailure)             VARIANT="on";   SERVICE="ad" ;;
  *) echo "Falha desconhecida: $FLAG (caos | cartFailure | productCatalogFailure | paymentFailure | adFailure)" >&2; exit 1 ;;
esac

# Flags a gravar, como objeto {"flag": "variante"}.
if [ "$FLAG" = "caos" ]; then
  if [ "$ACTION" = "reset" ]; then
    SET="$(printf '%s' "$CAOS_FLAGS" | jq -c 'map_values("off")')"
  else
    SET="$CAOS_FLAGS"
  fi
else
  if [ "$ACTION" = "reset" ]; then STATE="off"; else STATE="$VARIANT"; fi
  SET="$(jq -cn --arg f "$FLAG" --arg v "$STATE" '{($f): $v}')"
fi

current="$(curl -sf http://localhost:8080/feature/api/read)"
payload="$(printf '%s' "$current" | jq --argjson set "$SET" '
  reduce ($set | to_entries[]) as $e (.;
    .flags[$e.key].defaultVariant = $e.value |
    if (.flags[$e.key].targeting.if // null) != null then .flags[$e.key].targeting.if[1] = $e.value else . end) |
  {data: {"$schema": "https://flagd.dev/schema/v0/flags.json", flags: .flags}}
')"
curl -sf -X POST http://localhost:8080/feature/api/write -H 'Content-Type: application/json' -d "$payload" >/dev/null

# Caos: mais usuarios simulados no gerador de carga (Locust) para encher a tela; reset volta aos 2 usuarios.
if [ "$FLAG" = "caos" ]; then
  if [ "$ACTION" = "reset" ]; then USERS=2; else USERS=6; fi
  if curl -sf -X POST http://localhost:8080/loadgen/swarm -d "user_count=$USERS&spawn_rate=2" >/dev/null; then
    echo "Gerador de carga: $USERS usuarios simultaneos"
  else
    echo "Aviso: nao foi possivel ajustar o gerador de carga" >&2
  fi
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$ACTION" != "reset" ] && [ -f "$SCRIPT_DIR/generated/argos.env" ]; then
  . "$SCRIPT_DIR/generated/argos.env"
  if [ -n "${ARGOS_API_KEY:-}" ]; then
    # ARGOS_URL vem do argos.env: localhost no ambiente local, o endereço HTTPS no EC2 da AWS.
    curl -sf -X POST "${ARGOS_URL:-http://localhost:8081}/api/webhooks/deploys" \
      -H "X-API-Key: $ARGOS_API_KEY" -H 'Content-Type: application/json' \
      -d "{\"service\":\"$SERVICE\",\"version\":\"v1.4.2\",\"environment\":\"production\",\"commitSha\":\"a8f4c21e9b0d\",\"author\":\"ci-bot\",\"description\":\"Release automatica antes de $FLAG\"}" >/dev/null || true
    echo "Deploy v1.4.2 registrado no servico '$SERVICE' para correlacao DORA."
  fi
fi

echo "Feature flags na OpenTelemetry Demo: $(printf '%s' "$SET" | jq -r 'to_entries | map("\(.key)=\(.value)") | join(", ")')"
echo "O Argos monitora o fluxo OTLP em tempo real e abrira/resolvera os incidentes automaticamente em alguns segundos."
