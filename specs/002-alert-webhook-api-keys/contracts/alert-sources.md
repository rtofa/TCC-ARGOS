# Contrato: Fontes de alerta

Base `/api/alert-sources`. JWT de usuário. Leitura: todos os papéis; escrita: `ADMIN`, `EDITOR`.
Fonte removida ou de outra organização → 404.

## Representação

```json
{
  "id": "e1d4…",
  "name": "Grafana",
  "webhookPath": "/api/webhooks/alerts/e1d4…",
  "itemsPath": "$.alerts",
  "titlePath": "$.annotations.summary",
  "dedupKeyPath": "$.fingerprint",
  "statusPath": "$.status",
  "severityPath": "$.labels.severity",
  "servicePath": "$.labels.service",
  "descriptionPath": "$.annotations.description",
  "statusMap": { "firing": "firing", "resolved": "resolved" },
  "severityMap": { "critical": "SEV1", "warning": "SEV3" },
  "defaultSeverity": "SEV3",
  "createdBy": "3c7d…", "createdAt": "…", "updatedAt": "…"
}
```
Caminhos de campo são **relativos a cada item** da lista (`itemsPath`); sem `itemsPath`, relativos
ao corpo.

## Rotas

| Método | Rota | Resposta |
|---|---|---|
| POST | `/api/alert-sources` | 201 + representação |
| GET | `/api/alert-sources` | 200, lista (ordenada por nome) |
| GET | `/api/alert-sources/{id}` | 200 |
| PUT | `/api/alert-sources/{id}` | 200 (substitui todas as regras) |
| DELETE | `/api/alert-sources/{id}` | 204 (remoção lógica) |

Corpo de POST/PUT: a representação sem `id`, `webhookPath`, `createdBy`, `createdAt`, `updatedAt`.

## Validação (400)

- `name` obrigatório (1–100); `titlePath` e `dedupKeyPath` obrigatórios.
- Todo caminho informado deve ser JSONPath válido (até 200 caracteres).
- Valores de `severityMap` ∈ `SEV1`–`SEV4`; valores de `statusMap` ∈ `firing`, `resolved`;
  `defaultSeverity` ∈ `SEV1`–`SEV4` (padrão `SEV3`).
