#!/bin/sh
# Prepara o ambiente de demonstracao do Argos (idempotente):
#  1. cria a organizacao "Loja Demo" e o administrador (ou entra, se ja existirem);
#  2. cria uma chave de API para o OpenTelemetry Collector, salva em /out/argos.env.
# Credenciais de demonstracao - nunca use em producao.
set -eu

ARGOS_URL="${ARGOS_URL:-http://localhost:8081}"
DEMO_EMAIL="${DEMO_EMAIL:-admin@demo.argos}"
DEMO_PASSWORD="${DEMO_PASSWORD:-Argos@2026!}"
OUT_FILE="/out/argos.env"

apk add --no-cache -q curl jq >/dev/null

echo "Aguardando o backend em $ARGOS_URL ..."
tries=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ARGOS_URL/api/auth/login" \
        -H 'Content-Type: application/json' -d '{}')" != "000" ]; do
  tries=$((tries + 1))
  [ "$tries" -le 60 ] || { echo "Backend nao respondeu a tempo." >&2; exit 1; }
  sleep 3
done

register_body=$(jq -n --arg e "$DEMO_EMAIL" --arg p "$DEMO_PASSWORD" \
  '{organizationName: "Loja Demo", userName: "Admin Demo", email: $e, password: $p}')
token=$(curl -s -X POST "$ARGOS_URL/api/auth/register" -H 'Content-Type: application/json' \
  -d "$register_body" | jq -r '.token // empty')

if [ -n "$token" ]; then
  echo "Organizacao e usuario criados ($DEMO_EMAIL)."
else
  login_body=$(jq -n --arg e "$DEMO_EMAIL" --arg p "$DEMO_PASSWORD" '{email: $e, password: $p}')
  token=$(curl -s -X POST "$ARGOS_URL/api/auth/login" -H 'Content-Type: application/json' \
    -d "$login_body" | jq -r '.token // empty')
  [ -n "$token" ] || { echo "Nao foi possivel registrar nem entrar com $DEMO_EMAIL." >&2; exit 1; }
  echo "Usuario de demonstracao ja existia; login realizado."
fi

if [ -s "$OUT_FILE" ]; then
  echo "Chave de API ja gerada em demo/generated/argos.env; nada a fazer."
  exit 0
fi

key=$(curl -s -X POST "$ARGOS_URL/api/api-keys" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"name":"OpenTelemetry Collector (demo)"}' | jq -r '.key // empty')
[ -n "$key" ] || { echo "Falha ao criar a chave de API." >&2; exit 1; }

printf 'ARGOS_API_KEY=%s\nARGOS_URL=%s\n' "$key" "http://localhost:8081" > "$OUT_FILE"
echo "Chave de API criada e salva em demo/generated/argos.env."
echo "Console: http://localhost:3030  |  usuario: $DEMO_EMAIL  |  senha: $DEMO_PASSWORD"
