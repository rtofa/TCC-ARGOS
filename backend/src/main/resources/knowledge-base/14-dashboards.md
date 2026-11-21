---
title: Dashboards
---

# Dashboards

A tela **Dashboards** reúne painéis prontos sobre impacto no negócio, operação de incidentes, entrega de software (DORA) e saúde dos serviços. Os painéis são fixos e calculados automaticamente a partir dos incidentes, deploys, KPIs e telemetria: o Argos não tem editor de dashboards personalizados.

## Período

O seletor **Período** define a janela analisada: últimos 7, 14, 30 ou 90 dias.

## Visões

As abas "Visões do dashboard" organizam os painéis em quatro visões.

### Executivo

Para diretoria e quem acompanha o negócio:

- **Impacto estimado** no período, com quantos incidentes tiveram impacto calculado.
- **Incidentes críticos** (SEV1 e SEV2), com os minutos acumulados de impacto.
- **MTTR médio** e **Total de incidentes** do período.
- **Impacto financeiro diário**: prejuízo estimado por dia.
- **Impacto por serviço**: serviços com maior impacto.
- **KPIs afetados**: KPIs mais impactados, em unidades e valor.
- **Maiores incidentes do período**: tabela com **Incidente**, **Severidade**, **Abertura**, **Duração** e **Impacto**.

Sem KPIs cadastrados, a visão sugere configurar os **KPIs de negócio** para estimar o impacto financeiro.

### Operação

Para quem opera os incidentes:

- **Total de incidentes**, **Abertos agora**, **MTTA médio** e **MTTR médio**.
- **Distribuição por severidade**: incidentes por severidade, com MTTA e MTTR de cada uma.

### Entrega (DORA)

Para engenharia:

- **Frequência de deploy** (deploys por dia e nível DORA).
- **Lead time médio** (com o nível).
- **Change failure rate** (porcentagem e quantidade de deploys que falharam, com o nível).
- **MTTR** (com o nível).
- **Deploys correlacionados com incidentes**: deploys seguidos por incidentes, com **Serviço**, **Versão**, **Autor** e **Intervalo**.

Os níveis (Elite, Alto, Médio, Baixo) seguem os critérios descritos em "Visão geral (tendências SRE e métricas DORA)".

### Serviços

- **Serviços instrumentados (última 1 h)**: serviços que enviaram telemetria OTLP na última hora, com **Spans**, **Spans com erro**, **Logs** e **Logs de erro**. Sem telemetria recente, aparece "Nenhum serviço enviou telemetria OTLP na última hora."

## Quem pode ver

Todos os papéis veem os Dashboards. O papel **Executivo** foi pensado para quem acompanha principalmente a visão Executivo, sem operar incidentes.
