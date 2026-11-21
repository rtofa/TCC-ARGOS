---
title: Incidentes
---

# Incidentes

Um incidente representa uma falha ou degradação que afeta um ou mais serviços. A tela **Incidentes** é a central de gestão de crises: lista, cria e acompanha cada incidente até a resolução.

## Como um incidente é aberto

- **Manualmente**: Administradores e Editores clicam em **Novo incidente**.
- **Por alerta recebido via webhook**: uma ferramenta externa (Grafana, Prometheus/Alertmanager, Datadog ou o formato padrão do Argos) envia um alerta disparado. Esses incidentes têm a origem **Alerta** e o selo "Via alerta".
- **Pelo Monitor SLO OTLP**: o monitor automático abre o incidente quando um serviço passa do limite de taxa de erro ou de latência p95 configurado.

Sempre que um incidente é aberto, os canais de notificação ativos (Slack, Discord, E-mail) cuja severidade mínima for atingida são avisados.

## Lista de incidentes

### Filtros

- **Status**: botões Aberto, Investigando, Mitigado e Resolvido; é possível ligar vários ao mesmo tempo.
- **Severidade**: botões SEV1 a SEV4, também combináveis.
- **Aberto em**: Qualquer data, Últimas 24 h, Últimos 7 dias ou Últimos 30 dias.

### Colunas

| Coluna | Conteúdo |
|---|---|
| **Severidade** | SEV1 a SEV4 |
| **Incidente** | Título (clique para abrir o detalhe) |
| **Serviços** | Serviços afetados |
| **Status** | Situação atual |
| **Origem** | Manual ou Alerta |
| **Aberto** | Há quanto tempo foi aberto |
| **Tempo até resolver** | Duração até a resolução (MTTR do incidente) |

A lista é paginada com os botões **Anterior** e **Próxima**. Sem resultados, aparece "Nenhum incidente encontrado com esses filtros."

## Criar um incidente manualmente

Somente Administradores e Editores podem criar incidentes.

1. Na tela **Incidentes**, clique em **Novo incidente**.
2. Preencha:
   - **Título**: resumo curto do problema.
   - **Severidade**: SEV1 a SEV4.
   - **Serviços afetados**: nomes separados por vírgula (por exemplo, `checkout-api, orders-db`). Use os mesmos nomes enviados pela telemetria (`service.name`), para que o impacto, a telemetria e os deploys relacionados funcionem.
   - **Descrição**: contexto inicial (opcional).
3. Clique em **Criar incidente**.

## Severidades

| Severidade | Rótulo | Quando usar |
|---|---|---|
| SEV1 | Crítico | Serviço fora do ar ou falha generalizada |
| SEV2 | Alto | Degradação forte de uma funcionalidade importante |
| SEV3 | Médio | Degradação parcial, com contorno |
| SEV4 | Baixo | Impacto pequeno ou cosmético |

A severidade influencia o Quality Gate de deploys, a estimativa de impacto financeiro (quando não há telemetria suficiente) e quais canais de notificação são avisados.

## Status e ciclo de vida

O status segue o fluxo **Aberto → Investigando → Mitigado → Resolvido**:

- **Aberto**: detectado, ninguém começou a tratar ainda.
- **Investigando**: a equipe está analisando a causa.
- **Mitigado**: o impacto foi contido, mas a correção definitiva não terminou.
- **Resolvido**: o problema acabou.

Mudar um incidente resolvido para qualquer outro status o **reabre**, e a reabertura fica registrada na linha do tempo. Incidentes abertos por alerta ou pelo Monitor SLO são resolvidos automaticamente quando o alerta se recupera.

## Sala do incidente (detalhe)

Clicar em um incidente abre a página de detalhe, a "sala de guerra" do incidente.

### Cabeçalho

Mostra a severidade, o status, o selo "Via alerta" (quando aplicável) e o título. Administradores e Editores alteram o **Status** e a **Severidade** pelos seletores no topo da página.

### Aviso de deploy suspeito

Quando houve um deploy de um serviço afetado até 120 minutos antes da abertura do incidente (ou até 2 minutos depois, por atraso do pipeline), aparece o aviso **Deploy suspeito detectado** com os minutos de diferença, a versão, o autor, o serviço e o commit, além do link **Inspecionar deploy**.

### Descrição

O texto informado na abertura do incidente, ou gerado automaticamente pelo alerta ou pelo monitor.

### Telemetria do incidente

Mostra os **Traces com erro** e os **Logs de erro** dos serviços afetados no período do incidente, já filtrados, e o link **Abrir telemetria**. Se o incidente não tiver serviços afetados, o quadro pede para informá-los.

### Linha do tempo e comentários

A **Linha do tempo** registra tudo: abertura, reabertura, mudanças de status e de severidade, atribuições, alertas disparados e resolvidos e comentários. Cada evento mostra o autor ("Você", outro usuário ou "Argos (automático)") e quando aconteceu.

Administradores e Editores registram atualizações escrevendo no campo "Registre uma atualização ou comentário…" e clicando em **Comentar**. Comentários têm até 5.000 caracteres.

### Impacto no negócio

O quadro **Impacto no negócio** mostra o prejuízo estimado em R$ e as unidades perdidas por KPI (por exemplo, pedidos). Ele informa se o valor foi "Calculado com a taxa de erro real medida na telemetria" ou "Estimado pela severidade (sem telemetria suficiente do serviço)". Em incidentes ainda abertos, o valor continua crescendo. Sem KPIs ligados aos serviços afetados, o quadro sugere configurá-los.

### Detalhes

| Campo | Significado |
|---|---|
| **Serviços afetados** | Serviços impactados |
| **Responsável** | Quem está cuidando do incidente. **Atribuir a mim** define você; **Remover** tira o responsável |
| **Aberto em** | Data e hora da abertura |
| **Reconhecido em** | Quando o incidente foi reconhecido |
| **Resolvido em** | Quando foi resolvido |
| **Tempo até reconhecer** | MTTA do incidente |
| **Tempo até resolver** | MTTR do incidente |

### O que mudou?

Lista os deploys dos serviços afetados nas 24 horas antes da abertura, com o tempo relativo à abertura (por exemplo, "15 min antes da abertura").

### Alertas recebidos

Para incidentes abertos por alerta, lista cada alerta com a fonte, o estado (Disparado ou Resolvido), a chave de deduplicação, o número de ocorrências e o último recebimento. Quando vários alertas foram agrupados no incidente, aparece o indicador "N alertas agrupados".

## MTTA e MTTR

- **MTTA (tempo até reconhecer)**: tempo entre a abertura e o reconhecimento. O incidente é reconhecido quando sai do status Aberto ou quando recebe um responsável.
- **MTTR (tempo até resolver)**: tempo entre a abertura e a resolução.

Manter o status e o responsável atualizados é o que torna essas métricas confiáveis. As médias aparecem na tela Início, na Visão geral e nos Dashboards.

## Deduplicação e agrupamento de alertas

- Alertas com a mesma chave de deduplicação (`dedupKey`) atualizam o mesmo incidente em vez de abrir outro.
- Alertas diferentes do mesmo serviço recebidos em até 30 minutos são agrupados no mesmo incidente.
- Quando todos os alertas de um incidente informam recuperação, o incidente é resolvido automaticamente.

## Quem pode fazer o quê

| Ação | Administrador | Editor | Leitor | Executivo |
|---|---|---|---|---|
| Ver incidentes, linha do tempo e impacto | sim | sim | sim | sim |
| Criar incidente | sim | sim | não | não |
| Mudar status e severidade | sim | sim | não | não |
| Comentar e atribuir responsável | sim | sim | não | não |
