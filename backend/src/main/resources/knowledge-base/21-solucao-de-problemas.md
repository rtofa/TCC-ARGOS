---
title: Solução de problemas
---

# Solução de problemas

## Acesso ao console

### "Sua sessão expirou. Entre novamente."

A sessão expirou por tempo ou o servidor foi reiniciado. Entre de novo com e-mail e senha.

### Não consigo entrar

- Confira e-mail e senha.
- Sua conta pode ter sido desativada: peça a um Administrador para verificar em **Usuários** e, se for o caso, clicar em **Ativar**.
- Esqueceu a senha: peça a um Administrador para usar **Redefinir senha**.

### "Você não tem permissão para esta ação."

O seu papel não permite a ação. Leitores e Executivos apenas consultam; usuários e chaves de API são exclusivos de Administradores. Peça a um Administrador para ajustar o seu papel, se necessário.

### Não vejo as telas Usuários e Chaves de API

Elas aparecem só para Administradores.

## Telemetria

### Minha aplicação não aparece na Telemetria

1. Confira o protocolo: precisa ser `http/json` (OTLP sobre HTTP com JSON). `grpc` e `http/protobuf` não são aceitos.
2. Confira o endereço: `<URL da API>/webhooks/otlp` nos SDKs e no Collector (o exportador acrescenta `/v1/traces`, `/v1/logs`, `/v1/metrics`).
3. Confira a chave no cabeçalho `X-API-Key` e teste com `GET /api/webhooks/ping`.
4. Confira o período selecionado na tela Telemetria (padrão: 1 hora).
5. Use o **Testar conexão agora** do assistente Conectar App para validar o caminho.

### Recebo HTTP 401 ao enviar dados

A chave está ausente, errada ou foi revogada. Gere uma nova em **Chaves de API** e atualize a configuração.

### Recebo HTTP 413

O corpo passou do limite (10 MB para telemetria, 1 MB para alertas). Diminua o tamanho dos lotes do exportador ou da ferramenta.

### O serviço aparece com nome estranho, como "unknown_service"

Falta definir o nome do serviço. Configure `OTEL_SERVICE_NAME` (ou o atributo de recurso `service.name`).

### A topologia não mostra as ligações entre serviços

As ligações são desenhadas a partir dos traces distribuídos. Os serviços precisam propagar o contexto do trace entre si (as instrumentações automáticas do OpenTelemetry fazem isso nas chamadas HTTP) e enviar os spans para o Argos.

## Detecção de incidentes

### Um serviço com erros não abriu incidente

- Veja a regra que vale para ele em **Fontes de alerta**: uma regra específica, a regra `*` ou a regra padrão (janela de 90 segundos e mínimo de 3 spans com falha).
- A regra pode estar **Pausada**.
- O volume pode estar abaixo da **amostra mínima**.
- Os spans de erro precisam ter status de erro (`status.code = 2`).

### Estão abrindo incidentes demais

Ajuste as regras do Monitor SLO: aumente a taxa de erro máxima, a janela ou a amostra mínima, ou crie regras específicas para serviços ruidosos.

### Meus alertas do Grafana/Alertmanager não abrem incidentes

1. Confira o endereço do webhook (o da fonte ou `/api/webhooks/alerts`) e o cabeçalho `X-API-Key`.
2. Leia a resposta do webhook: alertas recusados vêm com `action: "REJECTED"` e o motivo (`title ausente`, `dedupKey ausente`, `status desconhecido`).
3. Em fontes com mapeamento, confira os caminhos JSONPath e a **Tradução de status**: todo valor de status enviado precisa estar traduzido.

### O incidente do alerta não foi resolvido

O incidente só é resolvido automaticamente quando **todos** os alertas agrupados nele enviam `resolved`. Confira na lista "Alertas recebidos" quais ainda estão disparados.

## Notificações

### A notificação não chegou

1. Confira se o canal está **Ativo** (não Pausado).
2. Confira a **Severidade mínima**: um canal com mínimo SEV2 não recebe SEV3 nem SEV4.
3. Clique em **Testar** e leia o resultado ("Falha ao entregar:" mostra o motivo).
4. Slack e Discord: confira se a URL do webhook ainda é válida.
5. E-mail: o envio depende do servidor SMTP configurado na instalação; sem ele, o teste falha.

## Deploys e Quality Gate

### O deploy não aparece na tela Deploys

- Confira a resposta do `POST /api/webhooks/deploys`: precisa ser HTTP 201. HTTP 400 indica campo inválido (por exemplo, `commitSha` que não é hexadecimal de 7 a 64 caracteres).
- Confira os filtros de serviço, ambiente e período da tela.

### O Quality Gate bloqueia sempre

Existe incidente SEV1 ativo (ou SEV2 aberto por alerta) no serviço. Incidentes **Mitigados** ainda bloqueiam: resolva-os. Sem o parâmetro `service`, qualquer incidente bloqueante da organização bloqueia.

### Não aparece o aviso de deploy suspeito

O aviso aparece quando um serviço afetado teve deploy até 120 minutos antes da abertura do incidente. Os nomes de serviço do deploy e do incidente precisam ser iguais.

## Impacto financeiro

### O impacto aparece como "—" ou zero

Nenhum serviço afetado está ligado a um KPI de negócio, ou o KPI não tem valor por unidade. Cadastre ou ajuste o KPI em **KPIs de negócio**, com os mesmos nomes de serviço do incidente.

## Lia

### A Lia respondeu "Não encontrei essa informação"

A documentação não cobre a pergunta, ou ela foi feita de um jeito muito diferente dos termos da documentação. Tente reformular usando os nomes das telas e funcionalidades.

### A Lia disse que só ajuda com o Argos

A pergunta foi entendida como fora do escopo da plataforma. Reformule deixando claro que é sobre o Argos.

### A Lia mostrou só trechos da documentação

Os modelos de linguagem estavam indisponíveis naquele momento. Os trechos mostrados são os mais relacionados à pergunta; tente novamente em instantes.
