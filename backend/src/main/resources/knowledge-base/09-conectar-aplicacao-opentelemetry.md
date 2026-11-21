---
title: Conectar aplicação com OpenTelemetry
---

# Conectar aplicação com OpenTelemetry

O Argos recebe traces, logs e métricas pelo padrão aberto **OpenTelemetry**, usando o protocolo **OTLP sobre HTTP com corpo JSON** (`http/json`). Não é preciso instalar agente proprietário: basta apontar o exportador OTLP da aplicação, ou do OpenTelemetry Collector, para o Argos.

## Assistente Conectar App

A tela **Conectar App** guia a instrumentação em três passos.

### Passo 1: Credencial e Stack

- **Nome do serviço monitorado**: o nome que vai aparecer no Argos (atributo `service.name`), por exemplo `checkout-api`.
- **Chave de API (X-API-Key)**: cole uma chave existente. Administradores podem clicar em **Gerar chave** para criar uma na hora. Os demais papéis devem pedir uma chave a um Administrador, gerada em **Chaves de API**.
- **Tecnologia da aplicação**: OpenTelemetry Collector / Docker, Java / Spring Boot, Node.js / Express, Python / FastAPI, Go (OTLP HTTP) ou Teste rápido via cURL.

### Passo 2: Configuração pronta para copiar

O assistente monta a configuração com o endereço do Argos e a chave informada. Copie e aplique no seu ambiente (exemplos abaixo).

### Passo 3: Detector ao vivo

O detector verifica a cada 3 segundos se o serviço já enviou dados e mostra **Conectado** quando os primeiros spans chegam. O botão **Testar conexão agora** envia um sinal de teste para validar a chave e o endereço. Quando o serviço aparece, o botão **Abrir Telemetria** leva à análise.

## Endereços de ingestão

| Sinal | Endereço |
|---|---|
| Traces | `POST /api/webhooks/otlp/v1/traces` |
| Logs | `POST /api/webhooks/otlp/v1/logs` |
| Métricas | `POST /api/webhooks/otlp/v1/metrics` |

Nos SDKs e no Collector, configure o endereço base `<URL da API>/webhooks/otlp`: o exportador acrescenta `/v1/traces`, `/v1/logs` e `/v1/metrics` sozinho.

Requisitos de cada envio:

- cabeçalho `X-API-Key` com uma chave de API ativa da organização;
- `Content-Type: application/json` (protocolo `http/json`; o formato protobuf não é aceito);
- corpo de até 10 MB por requisição.

## Exemplos de configuração

### OpenTelemetry Collector / Docker

```yaml
# otelcol-config.yaml
exporters:
  otlphttp/argos:
    endpoint: <URL da API>/webhooks/otlp
    encoding: json
    compression: none
    headers:
      X-API-Key: "<SUA_ARGOS_API_KEY>"

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlphttp/argos]
    logs:
      receivers: [otlp]
      exporters: [otlphttp/argos]
    metrics:
      receivers: [otlp]
      exporters: [otlphttp/argos]
```

O Collector é a opção recomendada quando há vários serviços: as aplicações enviam para o Collector e só ele conhece a chave do Argos.

### Java / Spring Boot (OpenTelemetry Java Agent)

```bash
OTEL_SERVICE_NAME=meu-servico-java
OTEL_EXPORTER_OTLP_PROTOCOL=http/json
OTEL_EXPORTER_OTLP_ENDPOINT=<URL da API>/webhooks/otlp
OTEL_EXPORTER_OTLP_HEADERS=X-API-Key=<SUA_ARGOS_API_KEY>

java -javaagent:opentelemetry-javaagent.jar -jar app.jar
```

### Node.js / Express

```bash
export OTEL_SERVICE_NAME="meu-servico-node"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/json"
export OTEL_EXPORTER_OTLP_ENDPOINT="<URL da API>/webhooks/otlp"
export OTEL_EXPORTER_OTLP_HEADERS="X-API-Key=<SUA_ARGOS_API_KEY>"

node --require @opentelemetry/auto-instrumentations-node/register server.js
```

### Python / FastAPI

Instale `opentelemetry-distro` e `opentelemetry-exporter-otlp-proto-http` e rode:

```bash
export OTEL_SERVICE_NAME="meu-servico-python"
export OTEL_EXPORTER_OTLP_PROTOCOL="http/json"
export OTEL_EXPORTER_OTLP_ENDPOINT="<URL da API>/webhooks/otlp"
export OTEL_EXPORTER_OTLP_HEADERS="X-API-Key=<SUA_ARGOS_API_KEY>"

opentelemetry-instrument uvicorn main:app --host 0.0.0.0 --port 8000
```

### Go

Use os exportadores HTTP do SDK (`otlptracehttp`, `otlploghttp`) com as variáveis padrão:

```bash
export OTEL_SERVICE_NAME="meu-servico-go"
export OTEL_EXPORTER_OTLP_ENDPOINT="<URL da API>/webhooks/otlp"
export OTEL_EXPORTER_OTLP_HEADERS="X-API-Key=<SUA_ARGOS_API_KEY>"
```

### Teste rápido via cURL

Envia um span de teste do serviço `minha-app-teste`:

```bash
curl -X POST "<URL da API>/webhooks/otlp/v1/traces" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <SUA_ARGOS_API_KEY>" \
  -d '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"minha-app-teste"}}]},
       "scopeSpans":[{"spans":[{"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7",
       "name":"GET /health","kind":2,"startTimeUnixNano":"1732100000000000000","endTimeUnixNano":"1732100001000000000",
       "status":{"code":1}}]}]}]}'
```

## Boas práticas

- Defina `OTEL_SERVICE_NAME` em todos os serviços, com nomes estáveis e iguais aos usados nos deploys, KPIs e incidentes.
- Prefira o Collector em produção e guarde a chave de API como segredo.
- Use uma chave por ambiente ou ferramenta, para poder revogar uma sem afetar as outras.
- Marque spans com erro com `status.code = 2` (ERROR): é isso que alimenta a taxa de erro, o Monitor SLO e o impacto financeiro medido pela telemetria.

## Problemas comuns

- **HTTP 401**: chave ausente, errada ou revogada. Confira o cabeçalho `X-API-Key`.
- **HTTP 413**: o corpo passou de 10 MB; diminua o tamanho dos lotes do exportador.
- **Nada aparece na Telemetria**: confira se o protocolo é `http/json` (não `grpc` nem `http/protobuf`) e se o endereço termina em `/webhooks/otlp`.
- **O serviço aparece com nome "unknown_service"**: falta definir `OTEL_SERVICE_NAME`.
- Para testar só a chave, use `GET /api/webhooks/ping` com o cabeçalho `X-API-Key`.
