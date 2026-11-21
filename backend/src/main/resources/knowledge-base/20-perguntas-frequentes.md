---
title: Perguntas frequentes
---

# Perguntas frequentes

## Geral

### O Argos substitui minha ferramenta de monitoramento?

Não necessariamente. O Argos recebe telemetria OpenTelemetry diretamente e também recebe alertas de ferramentas como Grafana, Prometheus/Alertmanager e Datadog por webhook. Ele centraliza a resposta a incidentes, relaciona falhas com deploys e calcula o impacto no negócio.

### Preciso instalar algum agente do Argos?

Não. O Argos usa o padrão aberto OpenTelemetry: basta apontar o exportador OTLP/HTTP (JSON) da aplicação ou do OpenTelemetry Collector para o Argos com uma chave de API.

### Quais linguagens são suportadas?

Qualquer linguagem com SDK do OpenTelemetry que exporte OTLP/HTTP em JSON. O assistente Conectar App traz configurações prontas para OpenTelemetry Collector/Docker, Java/Spring Boot, Node.js/Express, Python/FastAPI e Go.

### Meus dados ficam visíveis para outras empresas?

Não. Cada organização só vê os próprios dados, com isolamento garantido também no banco de dados (Row-Level Security).

## Incidentes

### Qual a diferença entre Mitigado e Resolvido?

Mitigado significa que o impacto foi contido, mas a correção definitiva ainda não terminou. Resolvido significa que o problema acabou. Incidentes mitigados ainda contam como ativos e ainda bloqueiam o Quality Gate se forem SEV1.

### Como reabro um incidente resolvido?

No detalhe do incidente, mude o **Status** para Aberto, Investigando ou Mitigado. A reabertura fica registrada na linha do tempo.

### Por que recebi um incidente que ninguém abriu?

Ele foi aberto automaticamente pelo Monitor SLO OTLP (taxa de erro ou latência p95 acima do limite) ou por um alerta recebido de uma ferramenta externa. A origem e a descrição do incidente mostram de onde veio.

### Por que vários alertas viraram um único incidente?

Alertas do mesmo serviço recebidos em até 30 minutos são agrupados no mesmo incidente para evitar ruído, e alertas com o mesmo `dedupKey` atualizam o mesmo incidente.

### O que conta como "reconhecer" um incidente?

O incidente é reconhecido quando sai do status Aberto ou quando alguém é atribuído como responsável. Isso marca o fim do MTTA.

### Leitores podem comentar em incidentes?

Não. Comentar, atribuir e mudar status ou severidade é permitido a Administradores e Editores.

## Deploys

### O Quality Gate bloqueou meu deploy. O que faço?

Veja na resposta (ou no quadro Quality Gate da tela Deploys) quais incidentes estão bloqueando. O bloqueio acontece com incidente SEV1 ativo, ou SEV2 aberto por alerta, no serviço. Resolva o incidente ou, se a severidade estiver errada, ajuste-a. Incidentes mitigados ainda bloqueiam.

### Por que o lead time aparece como "Sem amostra"?

O lead time só é calculado para deploys registrados com o campo `committedAt`. Envie esse campo no `POST /api/webhooks/deploys`.

## Impacto financeiro

### Por que o impacto de um incidente aparece como "—"?

Porque nenhum serviço afetado pelo incidente está ligado a um KPI de negócio. Cadastre o KPI em **KPIs de negócio** com os serviços que o sustentam, usando os mesmos nomes de serviço do incidente.

### O impacto usa dados reais ou estimados?

Quando há telemetria suficiente do serviço durante o incidente, o Argos usa a taxa de erro real medida. Sem telemetria suficiente, estima pela severidade (SEV1 100%, SEV2 50%, SEV3 20%, SEV4 5%). O quadro do incidente informa qual método foi usado.

## Notificações

### Quem recebe as notificações de incidente?

Os canais ativos (Slack, Discord e E-mail) cuja severidade mínima for atingida pelo incidente. Um canal com mínimo SEV2 recebe SEV1 e SEV2.

### Posso pausar um canal sem apagar?

Sim. Edite o canal e desmarque **Canal ativo para notificações automáticas**. Ele aparece como Pausado.

## Acesso

### Esqueci minha senha. Como recupero?

Peça a um Administrador da organização para redefinir a sua senha na tela **Usuários**. O Argos não envia recuperação por e-mail.

### Como dou acesso só de leitura para alguém?

Crie o usuário com o papel **Leitor** (ou **Executivo**, para quem acompanha principalmente os dashboards de negócio).

### Posso ter mais de um Administrador?

Sim, e é recomendado. A organização precisa ter pelo menos um Administrador ativo o tempo todo.

## Lia

### A Lia consegue ver meus incidentes?

Não. A Lia responde com base na documentação oficial do Argos e não acessa os dados da sua organização.

### A Lia pode errar?

A Lia só responde com base na documentação e cada resposta é conferida antes de ser exibida, mas confira as fontes citadas em decisões importantes. Quando a documentação não cobre a pergunta, ela diz que não encontrou.
