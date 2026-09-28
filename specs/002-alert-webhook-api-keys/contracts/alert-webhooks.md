# Contrato: Webhooks de alerta (INT-03)

Autenticação por **chave de API** (ver [api-keys.md](api-keys.md)). Organização = dona da chave.
`Content-Type: application/json`.

| Rota | Formato |
|---|---|
| `POST /api/webhooks/alerts` | formato padrão do Argos |
| `POST /api/webhooks/alerts/{sourceId}` | regras da fonte `sourceId` (ver [alert-sources.md](alert-sources.md)) |

## Formato padrão do Argos

Um alerta:
```json
{
  "title": "CPU acima de 90% no checkout-api",   // obrigatório
  "dedupKey": "cpu-checkout",                     // obrigatório
  "status": "firing",                              // firing | resolved (padrão firing)
  "severity": "critical",                          // critical|high|warning|info|SEV1..SEV4 (padrão SEV3)
  "service": "checkout-api",                       // texto ou lista, opcional
  "description": "CPU média 95% nos últimos 5 min" // opcional
}
```
Vários alertas: `{ "alerts": [ { ... }, { ... } ] }` (máximo 100).

## Respostas

| Status | Quando |
|---|---|
| 200 | JSON válido; resumo por alerta (mesmo que alguns tenham sido recusados) |
| 400 | corpo vazio, JSON inválido, lista com mais de 100 alertas |
| 401 | chave ausente, inexistente, revogada, ou JWT no lugar da chave |
| 404 | `sourceId` inexistente, removido ou de outra organização |
| 413 | corpo maior que 1 MB |

```json
{
  "received": 3, "accepted": 2, "rejected": 1,
  "results": [
    { "index": 0, "dedupKey": "cpu-checkout", "action": "OPENED",   "incidentId": "5f0c…" },
    { "index": 1, "dedupKey": "disk-db",      "action": "RESOLVED", "incidentId": "7a21…" },
    { "index": 2, "dedupKey": null,           "action": "REJECTED", "reason": "dedupKey ausente" }
  ]
}
```
`action` ∈ `OPENED`, `UPDATED`, `RESOLVED`, `IGNORED`, `REJECTED` (regras em
[data-model.md](../data-model.md#decisão-por-alerta-dentro-do-lock-de-r4)).

## Vínculos de um incidente

`GET /api/incidents/{id}/alerts` — JWT, papéis de leitura. Lista os vínculos alerta→incidente:
```json
[
  { "sourceId": null, "sourceName": "Formato padrão", "dedupKey": "cpu-checkout",
    "occurrences": 15, "firstSeenAt": "…", "lastSeenAt": "…", "closedAt": null }
]
```
Incidente manual → lista vazia. Incidente de outra organização → lista vazia.

## Mudanças aditivas no contrato do MON-10

- Incidentes criados por alerta: `source = "ALERT"`, `createdBy = null`.
- Eventos da linha do tempo ganham `actorType` (`USER` | `SYSTEM`) e `apiKeyId`; eventos
  automáticos têm `actorId = null`, `actorType = "SYSTEM"`.
- Novos tipos: `ALERT_TRIGGERED`, `ALERT_RESOLVED`.
