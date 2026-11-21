---
title: Fontes de alerta (webhooks)
---

# Fontes de alerta (webhooks)

Ferramentas de monitoramento como **Grafana**, **Prometheus/Alertmanager** e **Datadog** podem enviar alertas ao Argos por webhook. Cada alerta disparado abre ou atualiza um incidente, e cada alerta resolvido pode encerrá-lo. Tudo é configurado na tela **Fontes de alerta**. Administradores e Editores gerenciam as fontes; os demais papéis apenas consultam.

## Duas formas de receber alertas

1. **Formato padrão do Argos**: a ferramenta envia o JSON no formato do Argos para `POST /api/webhooks/alerts`. Não é preciso cadastrar nada.
2. **Fonte com mapeamento próprio**: para ferramentas com formato próprio, cadastre uma fonte que diz, com expressões JSONPath, onde está cada informação no JSON recebido. A ferramenta envia para `POST /api/webhooks/alerts/<id da fonte>`.

Em ambos os casos:

- autenticação pelo cabeçalho `X-API-Key` (chave gerada em **Chaves de API**, somente Administradores);
- corpo JSON de até 1 MB.

## Formato padrão do Argos

Um alerta, ou vários dentro de `{"alerts": [...]}`:

```json
{
  "alerts": [
    {
      "title": "CPU acima de 90% no checkout-api",
      "dedupKey": "cpu-checkout-api",
      "status": "firing",
      "severity": "critical",
      "service": "checkout-api",
      "description": "CPU em 97% há 5 minutos"
    }
  ]
}
```

| Campo | Obrigatório | Valores |
|---|---|---|
| `title` | sim | Título do incidente |
| `dedupKey` | sim | Identificador do alerta: envios com a mesma chave atualizam o mesmo incidente |
| `status` | não | `firing` (disparado) ou `resolved` (resolvido). Sem status, o alerta conta como disparado |
| `severity` | não | `critical` (SEV1), `high` (SEV2), `warning` (SEV3), `info` (SEV4), ou `sev1` a `sev4`. Sem severidade, vale SEV3 |
| `service` | não | Serviço afetado (use o mesmo nome da telemetria) |
| `description` | não | Descrição do incidente |

A tela Fontes de alerta tem o bloco "Formato padrão do Argos (sem configurar fonte)", com o endereço, o botão **Copiar URL** e um exemplo para **Testar pelo terminal**.

## Cadastrar uma fonte com mapeamento

1. Clique em **Nova fonte webhook**.
2. Informe o **Nome** da fonte (por exemplo, "Grafana produção").
3. Em "Onde está cada campo no JSON recebido (JSONPath)", preencha:
   - **Lista de alertas (se vierem vários por chamada)**: caminho da lista, por exemplo `$.alerts`. Quando preenchido, os demais caminhos são relativos a cada alerta da lista.
   - **Título do alerta** (obrigatório).
   - **Identificador único (deduplicação)** (obrigatório).
   - **Status (disparado / resolvido)**, **Severidade**, **Serviço afetado** e **Descrição** (opcionais).
4. Em **Tradução de status**, diga quais valores da ferramenta significam `firing` e `resolved`. Todo valor de status enviado pela ferramenta precisa estar traduzido; um valor sem tradução faz o alerta ser recusado com `status desconhecido`.
5. Em **Tradução de severidade**, diga quais valores viram SEV1, SEV2, SEV3 e SEV4.
6. Defina a **Severidade quando o alerta não informa**.
7. Salve. A fonte aparece em "Fontes de webhook configuradas" com o **Endereço do webhook**, a severidade padrão e a data de atualização. Use **Copiar URL** para levar o endereço à ferramenta.

### Exemplo: Prometheus Alertmanager e Grafana

O Alertmanager e os contact points de webhook do Grafana enviam uma lista `alerts` em que cada item tem `status`, `labels`, `annotations` e `fingerprint`. Um mapeamento típico:

| Campo da fonte | JSONPath |
|---|---|
| Lista de alertas | `$.alerts` |
| Título do alerta | `$.labels.alertname` |
| Identificador único | `$.fingerprint` |
| Status | `$.status` |
| Severidade | `$.labels.severity` |
| Serviço afetado | `$.labels.service` |
| Descrição | `$.annotations.summary` |

Traduções: status `firing` → firing e `resolved` → resolved; severidade `critical` → SEV1, `warning` → SEV3, `info` → SEV4.

### Configurar a ferramenta

Na ferramenta de monitoramento, crie um webhook para o endereço da fonte com o cabeçalho `X-API-Key: <sua chave>`. No Grafana, isso é um contact point do tipo Webhook com um cabeçalho HTTP personalizado; no Alertmanager, um `webhook_config` com o cabeçalho configurado.

## Resposta do webhook

O Argos responde com o resumo do processamento:

```json
{
  "received": 1,
  "accepted": 1,
  "rejected": 0,
  "results": [
    { "index": 0, "dedupKey": "cpu-checkout-api", "action": "OPENED", "incidentId": "…" }
  ]
}
```

| Ação | Significado |
|---|---|
| `OPENED` | Um novo incidente foi aberto |
| `UPDATED` | O incidente existente do mesmo alerta foi atualizado (nova ocorrência ou severidade) |
| `GROUPED` | O alerta foi agrupado no incidente ativo do mesmo serviço |
| `RESOLVED` | O alerta se recuperou e o incidente foi resolvido |
| `IGNORED` | Nada a fazer (por exemplo, recuperação de um alerta desconhecido) |
| `REJECTED` | O alerta foi recusado; o campo `reason` explica: `title ausente`, `dedupKey ausente`, `status desconhecido: <valor>` (status sem tradução) ou `alerta não é um objeto JSON` |

## Regras de deduplicação, agrupamento e resolução

- Alertas com o mesmo identificador (`dedupKey`) atualizam o mesmo incidente, contando as ocorrências e podendo elevar a severidade.
- Alertas diferentes do **mesmo serviço** em até **30 minutos** são agrupados no mesmo incidente.
- Quando **todos** os alertas de um incidente enviam `resolved`, o incidente é resolvido automaticamente. Se só parte deles se recuperou, a recuperação parcial fica registrada na linha do tempo.
- Se uma pessoa já tinha resolvido o incidente e o alerta dispara de novo, um novo incidente é aberto.

## Monitor SLO OTLP

A mesma tela também configura as regras do monitor automático sobre a telemetria. Veja "Monitor SLO OTLP (detecção automática)".
