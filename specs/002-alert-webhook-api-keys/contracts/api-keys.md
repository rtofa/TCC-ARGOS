# Contrato: Chaves de API (BASE-03)

Base `/api/api-keys`. Autenticação por **JWT de usuário**; somente papel `ADMIN`
(outros papéis → 403). Organização vem do JWT. Erros em `application/problem+json`.

## POST `/api/api-keys` — criar (201)

```json
{ "name": "Grafana produção" }        // obrigatório, 1–100 caracteres
```
Resposta (único momento em que `key` aparece):
```json
{
  "id": "0b6f…", "name": "Grafana produção", "prefix": "argos_k7x2Ab",
  "key": "argos_k7x2AbQ…(49 caracteres)",
  "createdAt": "2026-09-26T10:00:00Z"
}
```

## GET `/api/api-keys` — listar (200)

Ordenado por `createdAt` desc. Nunca contém `key`.
```json
[
  { "id": "0b6f…", "name": "Grafana produção", "prefix": "argos_k7x2Ab",
    "createdBy": "3c7d…", "createdAt": "2026-09-26T10:00:00Z",
    "lastUsedAt": "2026-09-26T10:05:00Z", "status": "ACTIVE", "revokedAt": null }
]
```

## POST `/api/api-keys/{id}/revoke` — revogar (200)

Resposta: o item da listagem com `status = "REVOKED"` e `revokedAt`. Revogar de novo mantém a
data original. Chave de outra organização → 404.

## Uso da chave (webhooks)

Cabeçalho `Authorization: Bearer argos_...` **ou** `X-API-Key: argos_...`, aceito **somente** em
`/api/webhooks/**`. Ausente, inexistente ou revogada → 401. Uma chave de API enviada a qualquer
outra rota é tratada como JWT inválido → 401. Um JWT enviado a `/api/webhooks/**` → 401.

## GET `/api/webhooks/ping` — validar chave (200)

Autenticado por chave de API. Serve para quem configura uma integração testar a chave.
```json
{ "organizationId": "8e2a…", "keyPrefix": "argos_k7x2Ab" }
```
Chave ausente, inválida ou revogada → 401. Também atualiza o "último uso".
