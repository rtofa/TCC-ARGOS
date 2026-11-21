---
title: Visão geral do Argos
---

# Visão geral do Argos

O Argos é uma plataforma SaaS de observabilidade e gestão de incidentes para sistemas baseados em microsserviços. Ele reúne, em um único console, tudo o que uma equipe precisa durante uma crise: a telemetria das aplicações, os alertas, os deploys recentes, os incidentes e o impacto financeiro de cada falha.

## O que o Argos faz

- **Recebe a telemetria das aplicações** (traces, logs e métricas) pelo padrão aberto OpenTelemetry, sem agentes proprietários.
- **Detecta problemas automaticamente**: o Monitor SLO OTLP abre incidentes quando a taxa de erro ou a latência p95 de um serviço passa do limite, e ferramentas externas (Grafana, Prometheus, Datadog e outras) podem enviar alertas por webhook.
- **Organiza a resposta a incidentes**: severidade, status, responsável, linha do tempo, comentários e as métricas MTTA e MTTR.
- **Relaciona falhas com mudanças**: registra os deploys do CI/CD, aponta o deploy suspeito de cada incidente, calcula as métricas DORA e bloqueia publicações arriscadas com o Quality Gate.
- **Fala a língua do negócio**: liga serviços a KPIs (por exemplo, pedidos por minuto) e estima quantos pedidos e quanto dinheiro cada incidente custou.
- **Avisa a equipe**: notifica canais de Slack, Discord e E-mail quando um incidente abre.
- **Ajuda a usar a plataforma**: a Lia, assistente virtual, responde dúvidas sobre o Argos com base nesta documentação.

## Fluxo típico

`alerta ou erro detectado → incidente aberto (sem duplicatas) → equipe avisada → deploy suspeito e telemetria com erro à mão → impacto em R$ estimado → status atualizado até a resolução`

## Organizações e isolamento dos dados

Cada empresa usa o Argos dentro de uma **organização**. Todos os dados (incidentes, deploys, telemetria, chaves de API, KPIs, canais de notificação e usuários) pertencem a uma única organização e nunca aparecem para outras. O isolamento é garantido no banco de dados com Row-Level Security (RLS), além das verificações da aplicação.

A documentação e a Lia são as mesmas para todas as organizações.

## Papéis de acesso

Cada usuário tem um papel:

- **Administrador**: acesso total, inclusive usuários e chaves de API.
- **Editor**: opera incidentes e configura alertas, regras SLO, canais de notificação e KPIs.
- **Leitor**: consulta tudo, sem alterar configurações.
- **Executivo**: consulta tudo, com foco em dashboards executivos, impacto financeiro e métricas DORA.

Os detalhes estão em "Usuários e papéis".

## Telas do console

O menu lateral reúne as telas:

| Tela | Para que serve |
|---|---|
| **Início** | Sala de operação em tempo real: saúde dos serviços, incidentes ativos e impacto estimado de cada um |
| **Visão geral** | Tendências do período para engenharia e SRE, métricas DORA e confiabilidade por serviço |
| **Incidentes** | Lista, criação e detalhe dos incidentes (a "sala de guerra") |
| **Deploys** | Histórico de publicações do CI/CD e Quality Gate |
| **Telemetria** | Topologia de serviços, métricas RED, traces e logs |
| **Dashboards** | Painéis prontos de visão executiva, operação, entrega (DORA) e serviços |
| **Conectar App** | Assistente para instrumentar uma aplicação com OpenTelemetry |
| **Integrações** | Catálogo de conectores e canais de notificação (Slack, Discord e E-mail) |
| **KPIs de negócio** | Métricas de negócio e os serviços que as sustentam |
| **Fontes de alerta** | Regras do Monitor SLO OTLP e fontes de alerta por webhook |
| **Chaves de API** | Credenciais para ferramentas externas (somente Administrador) |
| **Usuários** | Membros e papéis da organização (somente Administrador) |

As telas de Chaves de API e Usuários só aparecem no menu para Administradores.

## Navegação e preferências

- **Busca rápida**: o campo "Buscar páginas, incidentes e serviços" no topo da tela Início encontra telas, incidentes e serviços. A tecla `/` (fora de um campo de texto) leva direto à busca; as setas escolhem o resultado, Enter abre e Esc fecha.
- **Menu lateral retrátil**: o menu fica recolhido mostrando só os ícones e se expande ao passar o mouse. O botão de fixar, dentro do menu, mantém o menu sempre aberto. No celular, o menu abre pelo botão no topo.
- **Modo escuro**: o botão no canto superior direito alterna entre os temas claro e escuro. A escolha fica salva no navegador.
- **Tour guiado**: o botão **Tour guiado**, no canto superior direito, apresenta as principais telas. Ele também aparece automaticamente na primeira visita e pode ser pulado em **Pular tour**.
- **Guia de cada tela**: o quadro "Para que serve" no topo das telas explica o objetivo e os passos principais e pode ser recolhido em **Ocultar guia**.
- **Lia**: o balão com ponto de interrogação no canto inferior direito, presente em todas as telas, abre o chat com a Lia.
- **Sair**: o botão **Sair**, no rodapé do menu lateral, encerra a sessão. O rodapé também mostra o papel do usuário conectado.
