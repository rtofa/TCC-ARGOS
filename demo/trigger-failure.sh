#!/bin/sh
# Injeta (ou remove) uma falha na aplicacao de demo via API do flagd-ui, registra um deploy suspeito e avisa o Argos.
#   ./demo/trigger-failure.sh                               liga cartFailure (100%) e abre o incidente
#   ./demo/trigger-failure.sh productCatalogFailure         escolhe outra falha
#   ./demo/trigger-failure.sh productCatalogFailure reset   volta ao normal e resolve o alerta
# Requer curl e jq.
set -eu

FLAG="${1:-cartFailure}"
ACTION="${2:-on}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$HERE/generated/argos.env"

case "$FLAG" in
  cartFailure)           VARIANT="100%"; SERVICE="cart";            TITLE="Taxa de erro alta no servico de carrinho (cart)" ;;
  productCatalogFailure) VARIANT="on";   SERVICE="product-catalog"; TITLE="Falhas ao consultar o catalogo de produtos (product-catalog)" ;;
  paymentFailure)        VARIANT="100%"; SERVICE="payment";         TITLE="Pagamentos falhando no servico payment" ;;
  adFailure)             VARIANT="on";   SERVICE="ad";              TITLE="Falhas ao carregar anuncios no servico ad" ;;
  *) echo "Falha desconhecida: $FLAG (cartFailure | productCatalogFailure | paymentFailure | adFailure)" >&2; exit 1 ;;
esac

[ -s "$ENV_FILE" ] || { echo "Arquivo $ENV_FILE nao encontrado. Suba o Argos primeiro." >&2; exit 1; }
KEY="$(sed -n 's/^ARGOS_API_KEY=//p' "$ENV_FILE" | tr -d '\r\n ')"
URL="$(sed -n 's/^ARGOS_URL=//p' "$ENV_FILE" | tr -d '\r\n ')"

if [ "$ACTION" = "reset" ]; then STATE="off"; STATUS="resolved"; else STATE="$VARIANT"; STATUS="firing"; fi

current="$(curl -sf http://localhost:8080/feature/api/read)"
payload="$(printf '%s' "$current" | jq --arg f "$FLAG" --arg v "$STATE" '
  .flags[$f].defaultVariant = $v |
  if (.flags[$f].targeting.if // null) != null then .flags[$f].targeting.if[1] = $v else . end |
  {data: {"$schema": "https://flagd.dev/schema/v0/flags.json", flags: .flags}}
')"
curl -sf -X POST http://localhost:8080/feature/api/write -H 'Content-Type: application/json' -d "$payload" >/dev/null

if [ "$ACTION" != "reset" ]; then
  deploy_body="$(jq -n --arg svc "$SERVICE" --arg ver "3.1.0-$FLAG" --arg desc "Ativa alteracao associada ao cenario $FLAG" \
    '{service:$svc, version:$ver, environment:"production", commitSha:"4c44a889f0e1d2c3", author:"ci-bot", url:"https://github.com/rtofa/TCC-ARGOS/actions", description:$desc}')"
  curl -sf -X POST "$URL/api/webhooks/deploys" -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d "$deploy_body" >/dev/null
fi

body="$(jq -n --arg t "$TITLE" --arg k "demo-$FLAG" --arg s "$STATUS" --arg svc "$SERVICE" \
  --arg d "Falha controlada na feature flag '$FLAG' (variante '$STATE') na OpenTelemetry Demo." \
  '{title:$t, dedupKey:$k, status:$s, severity:"critical", service:$svc, description:$d}')"
curl -sf -X POST "$URL/api/webhooks/alerts" -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d "$body"
echo
echo "Flag $FLAG -> $STATE. Abra Incidentes no console do Argos: http://localhost:3030/incidents"
