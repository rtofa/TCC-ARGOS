---
title: API de integração (webhooks)
---

# API de integração (webhooks)

Ferramentas externas falam com o Argos por uma API HTTP de integração sob `/api/webhooks`. Ela recebe telemetria, alertas e deploys e responde ao Quality Gate. Este documento é a referência dessa API.

## Autenticação

Todas as chamadas exigem o cabeçalho:

```
X-API-Key: argos_...
```

A chave é criada por um Administrador em **Chaves de API** e identifica a organização: tudo o que for enviado com ela pertence àquela organização. Chave ausente, inválida ou revogada recebe **HTTP 401**.

As chaves só valem para os endereços `/api/webhooks/**`; o console usa login com e-mail e senha.

## Endereços

| Método | Endereço | Para quê | Sucesso |
|---|---|---|---|
| POST | `/api/webhooks/otlp/v1/traces` | Receber traces OTLP (JSON) | 200 |
| POST | `/api/webhooks/otlp/v1/logs` | Receber logs OTLP (JSON) | 200 |
| POST | `/api/webhooks/otlp/v1/metrics` | Receber métricas OTLP (JSON) | 200 |
| POST | `/api/webhooks/alerts` | Receber alertas no formato padrão do Argos | 200 |
| POST | `/api/webhooks/alerts/{sourceId}` | Receber alertas de uma fonte com mapeamento JSONPath | 200 |
| POST | `/api/webhooks/deploys` | Registrar um deploy | 201 |
| GET | `/api/webhooks/deploys/quality-gate?service=<serviço>` | Consultar se o deploy pode seguir | 200 (aprovado) ou 409 (bloqueado) |
| GET | `/api/webhooks/ping` | Testar a chave | 200 |

## Telemetria (OTLP/HTTP JSON)

- Corpo no formato OTLP JSON do OpenTelemetry (`resourceSpans`, `resourceLogs` ou `resourceMetrics`).
- `Content-Type: application/json`; protobuf não é aceito.
- Até **10 MB** por requisição; acima disso, **HTTP 413**.
- O nome do serviço vem do atributo de recurso `service.name`.
- Dados pessoais são mascarados antes de serem armazenados.

Exemplos de configuração por linguagem estão em "Conectar aplicação com OpenTelemetry".

## Alertas

- Corpo JSON de até **1 MB**; acima disso, **HTTP 413**.
- Formato padrão: um alerta ou `{"alerts": [...]}` com `title`, `dedupKey`, `status`, `severity`, `service` e `description`.
- Fontes com mapeamento: o formato da ferramenta, traduzido pelas expressões JSONPath da fonte.
- A resposta lista o que aconteceu com cada alerta (`OPENED`, `UPDATED`, `GROUPED`, `RESOLVED`, `IGNORED` ou `REJECTED`).

Detalhes em "Fontes de alerta (webhooks)".

### Exemplo

```bash
curl -X POST "<URL da API>/webhooks/alerts" \
  -H "X-API-Key: <sua chave>" -H "Content-Type: application/json" \
  -d '{"title":"Erro 500 no login","dedupKey":"login-500","status":"firing","severity":"high","service":"auth-service"}'
```

## Deploys

Corpo JSON:

```json
{
  "service": "payment",
  "version": "1.9.0",
  "environment": "production",
  "commitSha": "a1b2c3d",
  "author": "maria",
  "url": "https://github.com/empresa/payment/actions/runs/123",
  "description": "Novo meio de pagamento",
  "deployedAt": "2026-11-20T14:30:00Z",
  "committedAt": "2026-11-20T13:55:00Z"
}
```

`service` e `version` são obrigatórios. `commitSha` precisa ser hexadecimal de 7 a 64 caracteres. Dados inválidos recebem **HTTP 400** com a descrição do erro. Os demais limites estão em "Deploys e Quality Gate".

## Quality Gate

```bash
curl -s -o resposta.json -w "%{http_code}" \
  "<URL da API>/webhooks/deploys/quality-gate?service=payment" -H "X-API-Key: <sua chave>"
```

Resposta:

```json
{
  "allowed": false,
  "status": "BLOCKED",
  "service": "payment",
  "openSev1Incidents": 1,
  "openCriticalAlerts": 0,
  "reason": "Deploy bloqueado pelo Quality Gate: 1 incidente(s) SEV1 ativo(s) e 0 monitor(es) em alerta crítico afetando 'payment'.",
  "blockingIncidents": [
    { "id": "…", "title": "Checkout fora do ar", "severity": "SEV1", "status": "OPEN", "source": "MANUAL",
      "affectedServices": ["checkout-api", "payment"], "openedAt": "…" }
  ],
  "evaluatedAt": "…"
}
```

- **HTTP 200** com `"allowed": true` e `"status": "PASSED"`: pode publicar.
- **HTTP 409** com `"allowed": false` e `"status": "BLOCKED"`: não publique.

Sem o parâmetro `service`, a avaliação considera a organização inteira.

## Ping

```bash
curl "<URL da API>/webhooks/ping" -H "X-API-Key: <sua chave>"
```

Resposta com a organização e o prefixo da chave: `{"organizationId": "…", "keyPrefix": "argos_xxxxx"}`.

## Códigos de resposta

| Código | Significado |
|---|---|
| 200 | Processado com sucesso |
| 201 | Deploy registrado |
| 400 | Corpo inválido ou campo fora das regras (a resposta explica) |
| 401 | Chave de API ausente, inválida ou revogada |
| 409 | Quality Gate bloqueou o deploy |
| 413 | Corpo grande demais (10 MB para telemetria, 1 MB para alertas) |

Os erros seguem o formato padrão de problemas HTTP (RFC 7807), com os campos `status`, `title` e `detail`.
