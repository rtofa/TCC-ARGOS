#!/bin/sh
# Sobe a aplicacao de microsservicos (OpenTelemetry Demo / Astronomy Shop) enviando telemetria ao Argos.
# Pre-requisito: o Argos no ar (docker compose up --build), que gera demo/generated/argos.env.
#   ./demo/start-demo.sh          sobe a demo
#   ./demo/start-demo.sh stop     derruba a demo
set -eu

DEMO_TAG="3.1.0"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$HERE/opentelemetry-demo"
ENV_FILE="$HERE/generated/argos.env"

demo_compose() {
  (cd "$DEMO_DIR" && docker compose --env-file .env --env-file .env.override \
    -f compose.yaml -f "$HERE/compose.argos.yaml" "$@")
}

if [ "${1:-}" = "stop" ]; then demo_compose down; exit 0; fi

[ -s "$ENV_FILE" ] || { echo "Chave do Argos nao encontrada ($ENV_FILE). Rode 'docker compose up --build' primeiro." >&2; exit 1; }
KEY="$(sed -n 's/^ARGOS_API_KEY=//p' "$ENV_FILE" | tr -d '\r\n ')"
[ -n "$KEY" ] || { echo "ARGOS_API_KEY vazia em $ENV_FILE" >&2; exit 1; }

if [ ! -d "$DEMO_DIR" ]; then
  echo "Baixando a OpenTelemetry Demo $DEMO_TAG ..."
  git clone --quiet --depth 1 --branch "$DEMO_TAG" https://github.com/open-telemetry/opentelemetry-demo.git "$DEMO_DIR"
fi

sed "s|__ARGOS_API_KEY__|$KEY|" "$HERE/otelcol-config-extras.yml.template" \
  > "$DEMO_DIR/src/otel-collector/otelcol-config-extras.yml"

echo "Subindo a aplicacao de demonstracao (a primeira vez baixa varias imagens)..."
demo_compose up -d --remove-orphans

cat <<EOF

Loja (gera telemetria):       http://localhost:8080
Gerador de carga:             http://localhost:8080/loadgen/
Feature flags (falhas):       http://localhost:8080/feature
Console do Argos:             http://localhost:3030
Injetar uma falha:            ./demo/trigger-failure.sh   (use "reset" para voltar ao normal)
EOF
