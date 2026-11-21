---
title: Início (sala de operação)
---

# Início (sala de operação)

A tela **Início** é a sala de operação em tempo real: mostra a saúde atual dos serviços, os incidentes abertos agora e o impacto estimado de cada um. Ela se atualiza sozinha. Para analisar o histórico de um período, use a **Visão geral**.

## Topo da tela

- **Busca**: o campo "Buscar páginas, incidentes e serviços" encontra telas, incidentes e serviços pelo nome. A tecla `/` coloca o cursor na busca.
- **Indicador de status geral**: mostra "Todos os serviços operacionais" quando não há incidentes ativos, ou a quantidade de incidentes ativos (por exemplo, "7 incidentes ativos"). Clicar nele abre a lista de incidentes ativos (Aberto, Investigando e Mitigado) ou, se não houver nenhum, a Visão geral.
- **Guia da tela**: os botões **Conectar aplicação** e **Ver tendências e DORA (Visão geral)** levam às telas correspondentes.

## Cartões de resumo

| Cartão | O que mostra |
|---|---|
| **Incidentes ativos** | Quantos incidentes estão abertos agora e quantos deles são críticos (SEV1/SEV2) |
| **Perda estimada · 24 h** | Prejuízo estimado em R$ dos incidentes das últimas 24 horas (pedidos e receita em risco). Exige KPIs de negócio configurados; sem eles aparece "Nenhum KPI ligado aos serviços" |
| **Tempo até resolver · 7 dias** | Tempo médio para resolver os incidentes dos últimos 7 dias (MTTR), com o tempo médio até o reconhecimento logo abaixo |
| **Deploys · 7 dias** | Quantos deploys foram registrados nos últimos 7 dias e qual foi o último (serviço, versão e quando) |

## Incidentes ativos

A tabela "Incidentes ativos" lista os incidentes que ainda não foram resolvidos, dos mais graves para os menos graves, com:

- **Incidente**: severidade, título, serviços afetados e há quanto tempo foi aberto. Clique no título para abrir o incidente.
- **Status**: Aberto, Investigando ou Mitigado.
- **Impacto estimado**: prejuízo estimado em R$ até agora. Aparece "—" quando os serviços afetados não estão ligados a nenhum KPI de negócio.

O link **Ver todos** abre a tela **Incidentes**. Sem incidentes ativos, a tabela mostra "Nenhum incidente ativo: tudo operacional."

## Mapa de serviços

O quadro "Mapa de serviços e topologia (OpenTelemetry ao vivo)" mostra os serviços que enviam telemetria ou que foram citados em incidentes, em dois modos:

- **Topologia**: o grafo de dependências entre os serviços, desenhado a partir dos traces.
- **Grade**: um bloco por serviço com o seu estado.

A legenda indica o estado de cada serviço: **Operacional**, **Degradado / SEV3-4** (incidente ativo de severidade baixa ou média) e **Incidente SEV1/SEV2** (incidente crítico ativo). O link **Telemetria** abre a análise detalhada. Enquanto nenhum serviço enviou dados, o quadro explica que os serviços aparecem assim que enviarem telemetria OTLP ou forem citados em um incidente.

## Atividade recente

O quadro "Atividade recente" lista os últimos acontecimentos da organização: incidentes abertos, incidentes resolvidos e deploys registrados, cada um com link para o detalhe.
