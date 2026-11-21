---
title: Deploys e Quality Gate
---

# Deploys e Quality Gate

A tela **Deploys** responde à primeira pergunta de qualquer incidente: o que mudou, onde e quando. Ela mostra o histórico de publicações registradas pelo CI/CD e o Quality Gate, que bloqueia publicações enquanto houver incidente grave.

## Para que servem os deploys registrados

- Apontar o **deploy suspeito** no detalhe de cada incidente.
- Listar "O que mudou?" (deploys das 24 horas anteriores) em cada incidente.
- Calcular as **métricas DORA** (frequência de deploy, lead time e taxa de falha de mudança).
- Mostrar marcadores de deploy nos gráficos da Visão geral e da Telemetria.

## Histórico de deploys

### Filtros

- **Serviço**: nome do serviço (por exemplo, `checkout-api`); clique em **Filtrar**.
- **Ambiente**: Todos, production, staging ou development.
- **Período**: Qualquer data, Últimas 24 h, Últimos 7 dias ou Últimos 30 dias.
- **Limpar filtros** volta à lista completa.

### Colunas

| Coluna | Conteúdo |
|---|---|
| **Quando** | Data e hora do deploy |
| **Serviço** | Serviço publicado |
| **Versão** | Versão publicada |
| **Ambiente** | production, staging, development ou outro informado |
| **Commit** | Hash do commit (7 primeiros caracteres) |
| **Autor** | Quem fez o deploy |

A lista é paginada com **Anterior** e **Próxima**. Sem deploys registrados, a tela mostra como conectar o pipeline.

## Quality Gate

O quadro **Quality Gate de Deploy** mostra se publicar agora é seguro, com a quantidade de incidentes **SEV1 aberto(s)** e de **alerta(s) crítico(s)** que bloqueiam.

### Quando o deploy é bloqueado

O Quality Gate bloqueia quando existe, para o serviço consultado (ou para a organização inteira, se nenhum serviço for informado), um incidente ativo (Aberto, Investigando ou Mitigado) que seja:

- de severidade **SEV1**, aberto de qualquer forma; ou
- de severidade **SEV2** aberto por alerta (um alerta crítico em andamento).

Incidentes **mitigados ainda bloqueiam**: o bloqueio só termina quando o incidente é resolvido ou tem a severidade reduzida.

### Como o pipeline usa o Quality Gate

1. Antes de publicar, o pipeline chama `GET /api/webhooks/deploys/quality-gate?service=<serviço>` com o cabeçalho `X-API-Key`.
2. Resposta **HTTP 200**: deploy aprovado (`"status": "PASSED"`). O pipeline segue.
3. Resposta **HTTP 409**: deploy bloqueado (`"status": "BLOCKED"`), com o motivo e a lista dos incidentes que bloqueiam. O passo do pipeline deve falhar e impedir a publicação.

## Registrar um deploy

Depois de publicar, o pipeline envia o deploy:

- `POST /api/webhooks/deploys` com o cabeçalho `X-API-Key` e corpo JSON.
- A resposta é **HTTP 201** com o deploy registrado.

| Campo | Obrigatório | Descrição |
|---|---|---|
| `service` | sim | Nome do serviço publicado (até 100 caracteres). Use o mesmo `service.name` da telemetria |
| `version` | sim | Versão publicada (até 100 caracteres) |
| `environment` | não | Ambiente (até 50 caracteres); quando omitido, vale `production` |
| `commitSha` | não | Hash do commit, hexadecimal de 7 a 64 caracteres |
| `author` | não | Autor do deploy (até 200 caracteres) |
| `url` | não | Link para o pipeline ou a release (até 500 caracteres) |
| `description` | não | Descrição da mudança (até 2.000 caracteres) |
| `deployedAt` | não | Momento do deploy (ISO 8601); quando omitido, vale o momento do recebimento |
| `committedAt` | não | Momento do commit (ISO 8601); necessário para o lead time das métricas DORA |

### Exemplo com cURL

```bash
curl -X POST "$ARGOS_URL/api/webhooks/deploys" \
  -H "X-API-Key: $ARGOS_API_KEY" -H "Content-Type: application/json" \
  -d '{"service":"payment","version":"1.9.0","environment":"production","commitSha":"a1b2c3d","author":"maria"}'
```

### Exemplo com GitHub Actions

A tela Deploys, em "Como registrar deploys", traz um workflow pronto para GitHub Actions. A ideia é:

1. Guardar a chave de API como segredo do repositório (por exemplo, `ARGOS_API_KEY`).
2. Antes do passo de publicação, chamar o Quality Gate e falhar se a resposta for 409.
3. Depois da publicação, chamar `POST /api/webhooks/deploys` com o serviço, a versão (`${{ github.ref_name }}` ou similar), o commit (`${{ github.sha }}`) e o autor (`${{ github.actor }}`).

## Boas práticas

- Registre **todos** os deploys de produção, inclusive rollbacks, para que o deploy suspeito e as métricas DORA fiquem corretos.
- Envie sempre `commitSha` e `author`: eles aparecem no aviso de deploy suspeito.
- Envie `committedAt` para medir o lead time.
- Use os mesmos nomes de serviço da telemetria e dos incidentes.
