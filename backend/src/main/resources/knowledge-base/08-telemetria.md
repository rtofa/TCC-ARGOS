---
title: Telemetria
---

# Telemetria

A tela **Telemetria** permite inspecionar em profundidade os sinais enviados pelas aplicações via OpenTelemetry: topologia de microsserviços, métricas RED, traces e logs. Os dados pessoais já chegam mascarados (LGPD).

Para começar a receber dados, use o assistente **Conectar App** (veja "Conectar aplicação com OpenTelemetry").

## Filtros e controles

- **Período**: 15 minutos, 1 hora (padrão), 6 horas ou 24 horas.
- **Serviço**: Todos ou um serviço específico.
- **Atualizar**: recarrega os dados.
- **Conectar app**: abre o assistente de instrumentação.

Sem telemetria no período, a tela explica como conectar a aplicação e oferece o botão **Conectar aplicação agora**.

## Aba Serviços & Topologia

### Mapa de dependências

O "Grafo automático de dependência entre microsserviços (Service Topology Map)" é desenhado automaticamente a partir dos traces (relação entre `parent_span_id` e `span_id`). O serviço onde a falha nasceu (causa-raiz) aparece em **vermelho** e os serviços afetados em efeito cascata aparecem em **âmbar**. Clique em um serviço para abrir as Métricas RED dele.

### Tabela de serviços

| Coluna | Significado |
|---|---|
| **Serviço** | Nome do serviço (`service.name`) |
| **Spans** | Quantidade de spans recebidos no período |
| **Spans com erro** | Spans com status de erro |
| **Logs** | Quantidade de logs |
| **Logs de erro** | Logs de nível de erro |
| **Último sinal** | Quando o serviço enviou dados pela última vez |
| **Ações** | Atalhos **Métricas RED** e **Traces** do serviço (clicar no nome do serviço também abre os traces dele) |

## Aba Métricas RED

RED são as três métricas essenciais de um serviço:

- **Rate (taxa)**: requisições por minuto.
- **Errors (erros)**: porcentagem de requisições com erro.
- **Duration (duração)**: latência p50, p95 e p99.

Os gráficos são calculados a partir dos spans recebidos e mostram **marcadores de deploy**, para ver se uma publicação mudou o comportamento do serviço.

## Aba Traces

Lista as requisições distribuídas com **Requisição** (operação de entrada), **Serviços** envolvidos, **Duração** e **Quando**. O filtro "Somente com erro" mostra apenas traces que falharam.

Ao abrir um trace, a visualização em cascata (waterfall) mostra cada span na ordem em que aconteceu, com o serviço, a operação, a duração e o status. Abaixo, "Logs deste trace" lista os logs correlacionados pelo `traceId`.

## Aba Logs

Lista os logs dos serviços com horário, serviço, nível e mensagem.

- **Nível mínimo**: todos, INFO, WARN ou ERROR.
- **Buscar no texto do log**: procura um trecho na mensagem.

## Proteção de dados pessoais (LGPD)

Os dados pessoais são mascarados **na entrada**, antes de qualquer armazenamento, em traces, logs e métricas:

- CPF, CNPJ, e-mail, telefone e números de cartão de crédito;
- segredos: senhas, tokens, chaves de API e cabeçalhos de autorização;
- o final dos endereços IP (por exemplo, `192.168.x.x`).

Atributos com nomes sensíveis, como `password`, `token`, `authorization`, `cpf`, `cnpj`, `credit_card`, `card_number` e `cvv`, também têm o valor mascarado.

## Telemetria dentro dos incidentes

O detalhe de cada incidente mostra os traces com erro e os logs de erro dos serviços afetados no período do incidente, com o link **Abrir telemetria** para a análise completa.
