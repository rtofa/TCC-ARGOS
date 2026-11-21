---
title: Monitor SLO OTLP (detecção automática)
---

# Monitor SLO OTLP (detecção automática)

O **Monitor SLO OTLP** acompanha continuamente a telemetria de cada serviço e abre incidentes sozinho quando a taxa de erro ou a latência p95 passa do limite, muitas vezes antes de algum usuário reclamar. Ele é configurado na tela **Fontes de alerta**, no quadro "Regras de Monitoramento OpenTelemetry (SLO & Latência)". Administradores e Editores criam e alteram regras; os demais papéis apenas consultam.

## Regra padrão

Sem nenhuma regra cadastrada, o Argos aplica a todos os serviços uma regra padrão automática: **janela de 90 segundos** e **mínimo de 3 spans com falha**. Qualquer serviço com 3 ou mais spans de erro nos últimos 90 segundos ganha um incidente, com severidade calculada pela gravidade (veja abaixo).

## Campos de uma regra

| Campo | Descrição | Padrão |
|---|---|---|
| **Serviço (\* = todos)** | Nome do serviço, ou `*` para valer para todos os serviços sem regra própria | obrigatório |
| **Taxa de erro máx. (%)** | Limite de spans com erro, entre 0 e 100 (por exemplo, 2.0) | opcional |
| **Latência p95 máx. (ms)** | Limite do percentil 95 da duração dos spans (por exemplo, 800) | opcional |
| **Janela (segundos)** | Período avaliado, de 30 a 3600 segundos | 90 |
| **Amostra mínima (spans)** | Volume mínimo para avaliar, de 1 a 10.000, para não alarmar com pouco tráfego | 3 |
| **Severidade do incidente** | Severidade dos incidentes abertos pela regra | SEV2 |
| **Ativa** | Liga ou pausa a regra | ligada |

É obrigatório informar pelo menos um dos limites: taxa de erro ou latência p95.

Uma regra de um serviço específico tem prioridade sobre a regra `*`, que por sua vez tem prioridade sobre a regra padrão.

## Criar, editar e pausar regras

1. Em **Fontes de alerta**, preencha os campos da regra.
2. Clique em **Salvar regra OTLP**.
3. A regra aparece na tabela com **Serviço**, **Taxa de erro máx.**, **Latência p95 máx.**, **Janela / Amostra**, **Severidade** e **Status** (Ativa ou Pausada).
4. Use **Editar** para mudar ou pausar e **Remover** para apagar.

## O que acontece quando o limite é ultrapassado

### Taxa de erro

Se, na janela, o número de spans com erro atingir a amostra mínima e a porcentagem de erros atingir o limite, o Argos abre (ou atualiza) o incidente **"Erros detectados via OpenTelemetry em <serviço>"**, com a quantidade de falhas, a porcentagem, a janela e uma operação e mensagem de exemplo.

Na regra padrão, a severidade acompanha a gravidade:

| Situação na janela | Severidade |
|---|---|
| 30% ou mais de erros, ou 25 ou mais falhas | SEV1 |
| 10% ou mais de erros, ou 10 ou mais falhas | SEV2 |
| abaixo disso | SEV3 |

Nas regras cadastradas, vale a severidade escolhida na regra.

### Latência p95

Se o serviço teve pelo menos a amostra mínima de requisições e o p95 passou do limite, o Argos abre o incidente **"Degradação de latência p95 em <serviço>"**, com o p95 medido, o limite, o volume e a operação mais lenta, na severidade da regra.

### Recuperação automática

Quando o serviço volta ao normal (sem erros, ou com p95 abaixo do limite, com um volume mínimo de tráfego), o incidente automático é **resolvido sozinho** e a recuperação fica registrada na linha do tempo.

## Sem duplicatas

Enquanto o problema continua, o monitor atualiza o mesmo incidente em vez de abrir novos. Erros e latência do mesmo serviço em até 30 minutos podem ser agrupados no mesmo incidente.

## Dicas de ajuste

- Comece com a regra `*` com taxa de erro de 2% a 5% e ajuste por serviço.
- Para serviços com pouco tráfego, aumente a janela ou a amostra mínima para evitar alarmes falsos.
- Use latência p95 para serviços em que lentidão já é falha (checkout, login, pagamento).
- Defina SEV1 apenas para serviços cuja falha para o negócio: SEV1 aberto bloqueia deploys no Quality Gate.
