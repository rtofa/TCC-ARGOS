---
title: Primeiros passos
---

# Primeiros passos

Este guia leva uma organização do cadastro até o primeiro incidente detectado automaticamente, com a equipe avisada e o impacto em reais calculado.

## Checklist de implantação

1. Criar a organização e entrar no console.
2. Gerar uma chave de API.
3. Conectar a primeira aplicação com OpenTelemetry.
4. Ajustar as regras do Monitor SLO OTLP.
5. Conectar as ferramentas de alerta existentes (opcional).
6. Configurar os canais de notificação.
7. Registrar os deploys pelo CI/CD.
8. Cadastrar os KPIs de negócio.
9. Convidar a equipe.

## 1. Criar a organização e entrar

1. Na tela de entrada, clique em **Primeira vez aqui? Criar conta**.
2. Informe **Nome da organização**, **Seu nome**, **E-mail** e **Senha** e clique em **Criar conta**.
3. Quem cria a organização se torna o seu **Administrador**.
4. Nas próximas vezes, entre com e-mail e senha em **Entrar na sua conta**.

Na primeira visita o tour guiado apresenta as telas principais.

## 2. Gerar uma chave de API

Ferramentas externas (OpenTelemetry, alertas e CI/CD) se autenticam com uma chave de API enviada no cabeçalho `X-API-Key`.

1. Abra **Chaves de API** (somente Administrador).
2. Clique em **Nova chave**, dê um nome que identifique o uso (por exemplo, "Collector produção") e clique em **Gerar chave**.
3. Copie a chave na hora: ela é mostrada uma única vez.

O assistente **Conectar App** também tem o botão **Gerar chave**, disponível para Administradores.

## 3. Conectar a primeira aplicação

1. Abra **Conectar App**.
2. Informe o **Nome do serviço monitorado** e a **Chave de API (X-API-Key)**.
3. Escolha a **Tecnologia da aplicação**: OpenTelemetry Collector / Docker, Java / Spring Boot, Node.js / Express, Python / FastAPI, Go ou o teste rápido via cURL.
4. Copie a configuração pronta do Passo 2 para o seu ambiente.
5. No Passo 3, o detector ao vivo confirma quando o serviço começa a enviar dados. O botão **Testar conexão agora** envia um sinal de teste.

Depois disso os dados aparecem em **Telemetria** e o serviço surge no mapa da tela **Início**.

## 4. Ajustar a detecção automática

Mesmo sem configuração, o Argos aplica uma regra padrão a todos os serviços (janela de 90 segundos e mínimo de 3 spans com falha). Para limites próprios:

1. Abra **Fontes de alerta**.
2. Em "Regras de Monitoramento OpenTelemetry (SLO & Latência)", informe o serviço (ou `*` para todos), a **Taxa de erro máx. (%)** e/ou a **Latência p95 máx. (ms)**, a janela, a amostra mínima e a severidade.
3. Clique em **Salvar regra OTLP**.

## 5. Conectar as ferramentas de alerta existentes (opcional)

Se a empresa já usa Grafana, Prometheus/Alertmanager ou Datadog, configure um webhook dessas ferramentas para o Argos. Use o formato padrão do Argos ou crie uma fonte em **Fontes de alerta** > **Nova fonte webhook**, que traduz o formato da ferramenta com expressões JSONPath.

## 6. Configurar os canais de notificação

1. Abra **Integrações**.
2. Na área "Canais de notificação de incidentes", escolha Slack, Discord ou E-mail.
3. Informe o nome, o destino e a severidade mínima e clique em **Salvar canal**.
4. Clique em **Testar** para confirmar a entrega.

## 7. Registrar os deploys

Configure o pipeline de CI/CD para:

- consultar o Quality Gate antes de publicar (`GET /api/webhooks/deploys/quality-gate`);
- registrar o deploy depois de publicar (`POST /api/webhooks/deploys`).

A tela **Deploys** tem exemplos prontos em "Como registrar deploys", para GitHub Actions e para qualquer pipeline com cURL.

## 8. Cadastrar os KPIs de negócio

1. Abra **KPIs de negócio** e clique em **Novo KPI**.
2. Informe nome, unidade, volume normal por minuto e valor por unidade (por exemplo, "Pedidos", 12 por minuto, R$ 180 cada).
3. Adicione os serviços que sustentam o KPI e o peso de cada um.

A partir daí cada incidente mostra pedidos e reais em risco.

## 9. Convidar a equipe

Em **Usuários**, clique em **Adicionar usuário à organização**, informe nome, e-mail, senha inicial (mínimo de 6 caracteres) e papel, e clique em **Criar usuário**. Passe a senha inicial para a pessoa.
