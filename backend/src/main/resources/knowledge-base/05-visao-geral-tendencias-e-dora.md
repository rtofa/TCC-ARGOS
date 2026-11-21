---
title: Visão geral (tendências SRE e métricas DORA)
---

# Visão geral (tendências SRE e métricas DORA)

A tela **Visão geral** analisa o histórico de um período para engenharia e SRE: evolução dos incidentes, confiabilidade por serviço e as quatro métricas DORA de entrega. Ao contrário da tela Início, que mostra o estado ao vivo agora, ela olha para trás.

## Filtros

- **Período**: últimas 24 horas, últimos 7 dias, últimos 30 dias ou últimos 90 dias.
- **Serviço**: o campo "Filtrar serviço (ex: checkout)" restringe a análise a um serviço. Clique em **Aplicar** para filtrar e em **Limpar** para voltar a todos os serviços.

## Indicadores do período

| Indicador | Significado |
|---|---|
| **Abertos no período** | Quantos incidentes foram abertos no período escolhido |
| **Taxa de resolução** | Porcentagem desses incidentes que já foram resolvidos |
| **MTTA médio** | Tempo médio até reconhecer um incidente |
| **MTTR médio** | Tempo médio até resolver (restaurar) um incidente |

## Métricas DORA

As métricas DORA (DevOps Research & Assessment) medem a eficiência da entrega de software. Cada uma recebe um nível: **Elite**, **Alto**, **Médio**, **Baixo** ou **Sem amostra** (quando não há dados suficientes).

### Deployment Frequency (frequência de deploy)

Quantos deploys foram feitos no período, por dia e por semana.

| Nível | Critério |
|---|---|
| Elite | 1 ou mais deploys por dia |
| Alto | 1 ou mais deploys por semana |
| Médio | 1 ou mais deploys a cada 4 semanas |
| Baixo | menos que isso, ou nenhum deploy |

### Lead Time for Changes (tempo de entrega)

Tempo médio entre o commit e o deploy em produção. Só entram no cálculo os deploys registrados com o campo `committedAt`.

| Nível | Critério |
|---|---|
| Elite | menos de 1 hora |
| Alto | menos de 7 dias |
| Médio | menos de 30 dias |
| Baixo | 30 dias ou mais |

### Change Failure Rate (taxa de falha de mudança)

Porcentagem dos deploys seguidos por um incidente no mesmo serviço dentro da janela de falha, que por padrão é de 60 minutos após o deploy.

| Nível | Critério |
|---|---|
| Elite | até 5% |
| Alto | até 15% |
| Médio | até 30% |
| Baixo | acima de 30% |

### Mean Time to Restore (MTTR)

Tempo médio para resolver os incidentes do período. O cartão também mostra quantos incidentes foram resolvidos e o MTTA.

| Nível | Critério |
|---|---|
| Elite | menos de 1 hora |
| Alto | menos de 1 dia |
| Médio | menos de 7 dias |
| Baixo | 7 dias ou mais |

## Gráficos e tabelas

- **Incidentes abertos por dia & Marcadores de Deploy (14 dias)**: barras com os incidentes abertos em cada dia e o símbolo ◆ nos dias com deploy, para identificar padrões entre publicações e falhas. O link **Ver histórico de deploys** abre a tela Deploys.
- **Distribuição por severidade**: quantos incidentes de cada severidade (SEV1 a SEV4) no período.
- **Incidentes ativos por etapa**: quantos incidentes ativos estão em cada status (Aberto, Investigando, Mitigado).
- **Confiabilidade por serviço no período**: ranking dos serviços com colunas **Serviço**, **Incidentes**, **Críticos (SEV1/2)**, **Ativos agora**, **Deploys** e **Últimas 24 h**. Serve para priorizar melhorias de arquitetura. O link **Analisar métricas RED** abre a telemetria do serviço.
- **Deploys correlacionados com abertura de incidente**: deploys seguidos por incidente, com **Deploy**, **Autor**, **Incidente subsequente**, **Severidade** e **Intervalo após deploy**.
