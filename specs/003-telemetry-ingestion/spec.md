# Spec 003 — Ingestão e consulta de telemetria (OpenTelemetry)

## Objetivo

Fazer do Argos uma central que **recebe sinais de observabilidade** (traces, logs e métricas) de aplicações
instrumentadas com OpenTelemetry e os **mostra no contexto de um incidente**, ao lado dos alertas e dos deploys.
Hoje o Argos só gerencia incidentes; esta spec fecha a lacuna entre "o alerta disparou" e "o que aconteceu nos serviços".

## Escopo (P0)

| Item | Entrega |
|---|---|
| P0-1 | Endpoint de ingestão **OTLP/HTTP (JSON)**, autenticado por chave de API, gravando o `organization_id` da chave |
| P0-2 | API de consulta (serviços, traces, logs, métricas) e tela de telemetria, inclusive dentro do incidente |
| P0-3 | `docker-compose` completo: Argos + OTel Collector + aplicação de microsserviços open-source (OpenTelemetry Demo) |
| P0-4 | Seed e roteiro de demonstração (README) |

Fora de escopo: IA (P1), RLS nas novas tabelas (P1), protobuf (usa-se `encoding: json` no Collector), amostragem e retenção.

## Ingestão

O OTel Collector usa o exporter `otlphttp`, que acrescenta `/v1/traces`, `/v1/logs` e `/v1/metrics` ao endpoint:

| Método e rota | Corpo |
|---|---|
| `POST /api/webhooks/otlp/v1/traces` | `ExportTraceServiceRequest` em JSON |
| `POST /api/webhooks/otlp/v1/logs` | `ExportLogsServiceRequest` em JSON |
| `POST /api/webhooks/otlp/v1/metrics` | `ExportMetricsServiceRequest` em JSON |

- Autenticação: cabeçalho `X-API-Key` (mesmo mecanismo dos demais webhooks). A organização **sempre** vem da chave.
- Resposta `200` com `{"partialSuccess":{}}`, como o protocolo OTLP exige.
- `Content-Type` diferente de JSON → `415`. Corpo acima de 10 MB → `413`.
- Idempotência: spans repetidos (retentativas do Collector) são ignorados por `(organization_id, trace_id, span_id)`.
- O serviço de origem vem do atributo de recurso `service.name` (`desconhecido` se ausente).
- Métricas: `gauge` e `sum` viram um ponto por amostra; `histogram` vira dois pontos (`<nome>.count` e `<nome>.sum`).
- Limites de segurança: no máximo 64 atributos por registro, valores truncados em 1.000 caracteres e corpo de log em 10.000.

## Consulta (JWT, qualquer papel da organização)

| Rota | Descrição |
|---|---|
| `GET /api/telemetry/services` | Serviços vistos na janela, com contagem de spans/erros e de logs/erros |
| `GET /api/telemetry/traces` | Resumo de traces (`service`, `status=ERROR`, `minDurationMs`, `from`, `to`, `limit`) |
| `GET /api/telemetry/traces/{traceId}` | Todos os spans do trace |
| `GET /api/telemetry/logs` | Logs (`service`, `level`, `traceId`, `q`, `from`, `to`, `limit`) |
| `GET /api/telemetry/metrics/names` | Séries disponíveis |
| `GET /api/telemetry/metrics` | Pontos de uma série (`name`, `service`, `from`, `to`) |

Janela padrão: última hora. Toda consulta filtra por `organization_id` do token.

## Interface

- Página **Telemetria** (serviços, traces com detalhe em cascata, logs).
- Cartão **Telemetria do incidente** na tela do incidente: traces com erro e logs de erro dos serviços afetados,
  de 30 min antes da abertura até a resolução.

## Critérios de aceitação

1. Um span, log e métrica enviados com a chave da organização A aparecem para A e **não** aparecem para B.
2. Reenvio do mesmo lote não duplica spans.
3. Chave ausente/revogada → `401`; JWT em rota de webhook → `401`.
4. Com `docker compose up`, a demo gera telemetria real, visível na tela, sem passos manuais além do login.
