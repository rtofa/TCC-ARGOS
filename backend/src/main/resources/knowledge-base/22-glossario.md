---
title: Glossário
---

# Glossário

## Termos de incidentes

- **Incidente**: falha ou degradação que afeta um ou mais serviços, acompanhada até a resolução.
- **Severidade (SEV1 a SEV4)**: gravidade do incidente. SEV1 é crítico (serviço fora do ar), SEV2 alto, SEV3 médio e SEV4 baixo.
- **Status**: etapa do incidente: Aberto, Investigando, Mitigado ou Resolvido.
- **Mitigado**: impacto contido, mas correção definitiva ainda em andamento.
- **Reabertura**: volta de um incidente resolvido para um status ativo.
- **Responsável**: pessoa atribuída para cuidar do incidente.
- **Linha do tempo**: histórico de eventos e comentários de um incidente.
- **MTTA (Mean Time to Acknowledge)**: tempo médio entre a abertura e o reconhecimento de um incidente.
- **MTTR (Mean Time to Restore/Resolve)**: tempo médio entre a abertura e a resolução de um incidente.
- **Sala de guerra**: a página de detalhe do incidente, que reúne tudo o que é preciso para resolvê-lo.
- **Deploy suspeito**: deploy de um serviço afetado feito até 120 minutos antes da abertura do incidente.

## Termos de alertas e monitoramento

- **Alerta**: sinal de problema enviado por uma ferramenta externa ou gerado pelo Monitor SLO.
- **Fonte de alerta**: configuração que traduz o formato de uma ferramenta externa para o Argos, com expressões JSONPath.
- **JSONPath**: linguagem para apontar campos dentro de um JSON, por exemplo `$.labels.alertname`.
- **dedupKey (identificador de deduplicação)**: identificador de um alerta; envios com o mesmo valor atualizam o mesmo incidente.
- **Agrupamento**: junção, em um único incidente, de alertas do mesmo serviço recebidos em até 30 minutos.
- **SLO (Service Level Objective)**: meta de qualidade de um serviço, como taxa de erro ou latência máxima.
- **Monitor SLO OTLP**: monitor do Argos que avalia a telemetria e abre incidentes quando um SLO é violado.
- **Janela**: período (em segundos) que o monitor avalia a cada vez.
- **Amostra mínima**: volume mínimo de spans para a regra ser avaliada.
- **Latência p95**: tempo abaixo do qual estão 95% das requisições; indica a experiência dos usuários mais afetados por lentidão.
- **Webhook**: chamada HTTP que uma ferramenta faz para avisar outra de um evento.

## Termos de telemetria

- **Observabilidade**: capacidade de entender o estado de um sistema pelos dados que ele emite.
- **OpenTelemetry (OTel)**: padrão aberto para gerar e enviar traces, logs e métricas.
- **OTLP**: protocolo do OpenTelemetry para enviar telemetria; o Argos aceita OTLP sobre HTTP com JSON.
- **OpenTelemetry Collector**: serviço que recebe telemetria das aplicações e a repassa a destinos como o Argos.
- **Trace**: registro do caminho completo de uma requisição por vários serviços.
- **Span**: uma etapa de um trace (por exemplo, uma chamada HTTP ou uma consulta ao banco), com duração e status.
- **traceId**: identificador que liga todos os spans e logs de uma mesma requisição.
- **Log**: mensagem registrada por uma aplicação, com horário e nível (INFO, WARN, ERROR).
- **Métrica**: valor numérico medido ao longo do tempo.
- **service.name**: atributo do OpenTelemetry com o nome do serviço; é o nome que aparece no Argos.
- **Métricas RED**: Rate (requisições por minuto), Errors (porcentagem de erros) e Duration (latência p50, p95 e p99).
- **Topologia**: mapa automático das dependências entre serviços, desenhado a partir dos traces.
- **Causa-raiz**: serviço onde a falha começou; aparece em vermelho na topologia.
- **Efeito cascata**: serviços afetados pela falha de outro; aparecem em âmbar.
- **Waterfall (cascata)**: visualização dos spans de um trace em ordem, com a duração de cada um.

## Termos de entrega

- **Deploy**: publicação de uma nova versão de um serviço.
- **CI/CD**: integração e entrega contínuas; os pipelines que testam e publicam o software.
- **Quality Gate**: verificação que bloqueia deploys enquanto houver incidente SEV1 ativo (ou SEV2 aberto por alerta) no serviço.
- **Métricas DORA**: quatro métricas de entrega de software: Deployment Frequency, Lead Time for Changes, Change Failure Rate e Mean Time to Restore.
- **Lead time**: tempo entre o commit e o deploy em produção.
- **Change Failure Rate**: porcentagem de deploys seguidos por incidente no mesmo serviço em até 60 minutos.
- **Commit SHA**: identificador hexadecimal de um commit no Git.

## Termos de negócio

- **KPI de negócio**: indicador que a empresa acompanha, como pedidos por minuto.
- **Volume normal por minuto**: quanto o KPI produz por minuto em operação normal.
- **Peso**: quanto do KPI se perde se um serviço parar totalmente.
- **Impacto estimado**: unidades e valor em R$ perdidos durante um incidente.
- **Degradação**: fração do serviço que deixou de funcionar, medida pela taxa de erro ou estimada pela severidade.

## Termos de acesso e segurança

- **Organização**: espaço isolado de uma empresa no Argos.
- **Multi-tenant**: arquitetura em que várias organizações usam a mesma plataforma com dados isolados.
- **RLS (Row-Level Security)**: recurso do banco de dados que garante que cada organização só acesse as próprias linhas.
- **RBAC**: controle de acesso por papéis (Administrador, Editor, Leitor e Executivo).
- **Chave de API**: credencial (começa com `argos_`) usada por ferramentas no cabeçalho `X-API-Key`.
- **LGPD**: Lei Geral de Proteção de Dados; o Argos mascara dados pessoais na telemetria e nas perguntas à Lia.

## Lia

- **Lia**: assistente virtual do Argos, que responde dúvidas com base na documentação oficial.
- **Fonte**: trecho da documentação citado em uma resposta da Lia.
- **Base de conhecimento**: conjunto de documentos oficiais usados pela Lia.
- **Busca híbrida**: busca que combina palavras (busca textual) e significado (busca semântica) para achar os trechos mais relevantes.
