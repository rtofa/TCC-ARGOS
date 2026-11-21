---
title: Integrações e notificações
---

# Integrações e notificações

A tela **Integrações** é o catálogo central de conectores da organização. Ela mostra o status de cada integração e configura os avisos automáticos enviados quando um incidente abre.

## Catálogo de integrações

Os conectores podem ser filtrados pelas categorias **Todas**, **Notificações**, **Telemetria**, **Alertas & SLO**, **CI/CD** e **Negócio**. Cada cartão mostra o status atual e um atalho para configurar:

| Conector | O que faz | Atalho |
|---|---|---|
| **Slack (Webhook)** | Envia alertas automáticos via Incoming Webhook com severidade, serviços afetados e link direto para o incidente | Configurar Slack |
| **Discord (Webhook)** | Publica mensagens coloridas por severidade em canais do Discord na abertura do incidente | Configurar Discord |
| **E-mail (SMTP)** | Dispara avisos de incidentes por e-mail para listas de plantão | Configurar E-mail |
| **OpenTelemetry (OTLP/HTTP)** | Ingestão de traces, logs e métricas com mascaramento LGPD, topologia automática e gráficos RED. O botão **Ver config OTel** mostra a configuração do Collector | Abrir Telemetria |
| **Grafana, Alertmanager & Webhooks** | Recebe alertas externos com extração JSONPath, deduplicação e agrupamento por serviço | Gerenciar fontes |
| **Monitor SLO OTLP (Erro & p95)** | Avalia taxa de erro e latência p95 dos spans para abrir incidentes automaticamente | Configurar regras |
| **GitHub Actions & Quality Gate** | Registra deploys para as métricas DORA e bloqueia releases com incidente SEV1 aberto | Abrir Deploys |
| **KPIs de Negócio & Impacto** | Mapeia pedidos/min e receita por serviço para estimar o custo de cada incidente | Configurar KPIs |
| **Chaves de API (X-API-Key)** | Emite credenciais para coletores, webhooks de alerta e pipelines (cartão visível só para Administradores) | Gerenciar chaves |

Exemplos de status: "Não configurado", "Aguardando traces/logs", "Formato padrão ativo", "Usando limite padrão", "Sem deploys registrados", "N KPI(s) cadastrados", "Disponível para máquinas".

## Canais de notificação

Sempre que um incidente é aberto (manualmente, por alerta ou pelo Monitor SLO OTLP), o Argos notifica os **canais ativos** cuja **severidade mínima** for atingida.

### Tipos de canal

| Tipo | Destino |
|---|---|
| **Slack** | **URL do Webhook**: o endereço do Incoming Webhook do canal do Slack |
| **Discord** | **URL do Webhook**: o endereço do webhook do canal do Discord |
| **E-mail** | **Destinatários (separados por vírgula)**: um ou mais e-mails |

### Campos

- **Tipo**: Slack, Discord ou E-mail.
- **Nome do canal**: até 100 caracteres (por exemplo, "Plantão SRE").
- **URL do Webhook** ou **Destinatários**: até 500 caracteres. URLs precisam começar com `http://` ou `https://` e ter um endereço válido; cada e-mail precisa ser válido.
- **Severidade mínima**: só incidentes dessa severidade ou mais graves geram aviso. Com mínimo SEV2, o canal recebe SEV1 e SEV2, mas não SEV3 nem SEV4.
- **Canal ativo para notificações automáticas**: liga ou pausa o canal sem apagá-lo.

### Adicionar um canal

1. Em **Integrações**, na área "Canais de notificação de incidentes (Slack, Discord e E-mail)", escolha o tipo (ou use os atalhos **Configurar Slack**, **Configurar Discord**, **Configurar E-mail**).
2. Preencha nome, destino e severidade mínima.
3. Clique em **Adicionar canal** (ou **Salvar canal** ao editar).
4. Clique em **Testar** para enviar uma notificação de teste na hora.

### Tabela de canais

| Coluna | Conteúdo |
|---|---|
| **Canal** | Nome e tipo |
| **Destino** | URL do webhook ou destinatários |
| **Severidade** | Severidade mínima |
| **Último envio** | Quando o canal recebeu o último aviso |
| **Status** | Ativo ou Pausado |
| **Ações** | **Testar**, **Editar** e **Remover** |

Administradores e Editores criam, editam, testam e removem canais; Leitores e Executivos apenas consultam.

## Conteúdo do aviso

O aviso traz a severidade, o título e a descrição do incidente, a origem (manual ou alerta), os serviços afetados e um link para abrir o incidente no console.

- **Slack e Discord**: mensagem colorida conforme a severidade.
- **E-mail**: assunto no formato `[Argos][SEV1] Título do incidente`.
- **Teste**: os envios de teste chegam com o prefixo **[TESTE]** e usam um incidente fictício.

O resultado do teste aparece na tela: "Notificação de teste entregue com sucesso." ou "Falha ao entregar:" seguido do motivo.

## Envio de e-mail

O canal de E-mail depende de um servidor SMTP configurado na instalação do Argos (variáveis `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER` e `SMTP_PASS`). Sem SMTP, o teste de e-mail falha, mas os canais de Slack e Discord continuam funcionando.

## Como criar os webhooks

- **Slack**: crie um app no Slack com "Incoming Webhooks" ativado, escolha o canal e copie a URL gerada (começa com `https://hooks.slack.com/`).
- **Discord**: nas configurações do canal, em Integrações > Webhooks, crie um webhook e copie a URL.
