# Pull Request: Implementação Completa da Observabilidade OTLP, Isolamento Multi-Tenant RLS, Métricas DORA, Quality Gate CI/CD e Suíte Experimental (Quadros 1 a 4 do TCC)

## 📌 Visão Geral & Contexto

Esta Pull Request consolida a implementação completa dos requisitos de engenharia, arquitetura e validação experimental do **TCC-ARGOS**, integrando os recursos prometidos no documento acadêmico (**Grupo 1 — Prioridade P1**, Quadros 1, 2, 3 e 4) e os recursos de observabilidade de alto impacto visual (**Grupo 2 — Diferenciais de Banca**).

A branch `003-telemetry-ingestion` eleva o Argos do status de protótipo de gestão de incidentes para uma **plataforma completa de observabilidade e confiabilidade em microsserviços**, compatível nativamente com o padrão CNCF OpenTelemetry (OTLP/HTTP), com isolamento estrito de dados no banco de dados (*Row-Level Security*), rastreabilidade de entrega contínua com Quality Gate e metodologia experimental rigorosamente reproduzível.

---

## 🚀 Resumo das Entregas e Funcionalidades

### 1. Grafo Automático de Dependência de Microsserviços (Service Topology Map)
* **Objetivo Acadêmico**: Responder à pergunta clássica de banca: *"Em uma arquitetura distribuída, como o engenheiro identifica visualmente a causa-raiz e diferencia do efeito cascata?"*
* **Implementação Backend**: Endpoint `GET /api/telemetry/topology` em `TelemetryQueryService.java`. Correlaciona `parent_span_id -> span_id` dentro do mesmo `trace_id` nos spans OTLP recebidos.
* **Inteligência de Causa-Raiz**: O algoritmo identifica os nós finais que geraram erros HTTP 5xx / status code 2 sem propagar chamadas descendentes com falha (`rootCause = true`), destacando o nó agressor em **vermelho pulsante** e os serviços a montante em **âmbar** (efeito cascata).
* **Frontend**: Componente `ServiceTopologyGraph.jsx` interativo em SVG puro (sem dependências externas pesadas), renderizando setas direcionadas com volume de chamadas, taxa de erro (%) e latência p95.

### 2. Métricas RED por Serviço em Série Temporal
* **Implementação Backend**: Endpoint `GET /api/telemetry/red` calculando Rate (req/min), Errors (%) e Duration (`p50`, `p95`, `p99` em milissegundos) em buckets temporais configuráveis.
* **Frontend**: Componente `RedCharts.jsx` exibido na tela de Telemetria e no detalhe de serviços, com linhas de tendência e **marcadores visuais verticais para Deploys (azul) e Incidentes (vermelho)**, viabilizando correlação imediata entre entrega e degradação.

### 3. Regras de Monitoramento OTLP Configuráveis pela UI (SLO Rules)
* **Migração de Banco**: `V6_1__telemetry_rules.sql` criando a tabela `telemetry_rules`.
* **Motor de Avaliação**: `TelemetryMonitor.java` com agendamento contínuo avaliando janelas móveis por serviço tanto para **taxa de erro (%)** quanto para **degradação de latência p95 (ms)**.
* **Interface**: Gerenciamento completo de regras (CRUD) na tela de Alertas (`AlertSources.jsx`).

### 4. Métricas DORA Oficiais e Correlação Deploy-Incidente (Quadros 1 e 4, Seções 3.4.4 e 3.4.5)
* **Endpoint DORA**: `GET /api/metrics/dora` implementado em `DoraMetricsService.java` e `DoraMetricsController.java`.
* **As 4 Métricas Calculadas Oficialmente**:
  1. **Deployment Frequency**: Total de deploys por período (dia/semana) e classificação (*Elite*, *High*, *Medium*, *Low*);
  2. **Lead Time for Changes**: Tempo decorrido entre o commit (`committedAt`) e a publicação em produção (`deployedAt`);
  3. **Change Failure Rate**: Percentual de deploys que resultaram em incidentes abertos no mesmo serviço dentro de uma janela de tolerância;
  4. **Mean Time to Restore (MTTR)**: Tempo médio entre abertura e resolução dos incidentes correlacionados.
* **Correlação Visual & Banner Proativo**:
  - Marcadores de losango (`◆`) nos gráficos diários de incidentes (`IncidentsPerDay.jsx`) e painel DORA na Visão Geral (`Overview.jsx`).
  - Banner automático no detalhe do incidente (`IncidentDetail.jsx`): *"Deploy suspeito detectado X min antes da abertura deste incidente (versão por autor)"*.

### 5. Isolamento Multi-Tenant Completo via Row-Level Security no PostgreSQL (Quadro 2, Seção 3.1.3)
* **Migração V7**: `V7__enable_rls_all_tables.sql` ativando `ENABLE ROW LEVEL SECURITY` e `FORCE ROW LEVEL SECURITY` com políticas `USING` e `WITH CHECK` baseadas na variável de sessão `app.current_tenant` em **todas as 11 tabelas de tenant**:
  - `users`, `incidents`, `incident_events`, `api_keys`, `alert_sources`, `alert_incident_links`, `deploys`, `telemetry_spans`, `telemetry_logs`, `telemetry_metrics`, `telemetry_rules`.
* **Defesa em Profundidade**: Criação da role `argos_tenant NOLOGIN NOBYPASSRLS` para que a imposição ocorra no próprio motor do PostgreSQL, mesmo se uma query na camada de aplicação omitir a cláusula `WHERE organization_id = ?`.
* **Suíte de Testes `MultiTenantIsolationIT`**:
  - Valida metadados de RLS no catálogo (`pg_class` e `pg_policies`);
  - Valida bloqueio estrito no banco (`ERROR: new row violates row-level security policy`);
  - Valida isolamento em nível de API em todas as rotas (tentativas de leitura cruzada por UUID direto de incidentes, traces, deploys e chaves de API).

### 6. Pipeline CI/CD com Segurança e Quality Gate (Quadro 4, Seção 3.4.3)
* **Monitoramento Contínuo**: Adicionado `.github/dependabot.yml` para Maven, NPM e GitHub Actions.
* **Pipeline `.github/workflows/ci.yml`**:
  - Auditoria SCA de dependências JavaScript (`npm audit --omit=dev --audit-level=high`);
  - Varredura de vulnerabilidades CVE e misconfigurations estáticas (`aquasecurity/trivy-action`);
  - Quality Gate automatizado antes de autorizar o deploy.
* **Quality Gate de Deploy**:
  - Endpoint `GET /api/webhooks/deploys/quality-gate?service=...&strict=true` (retorna `HTTP 409 Conflict` bloqueando a pipeline se houver incidente SEV1 aberto ou alerta crítico);
  - Endpoint de consulta para UI: `GET /api/deploys/quality-gate`;
  - Scripts CLI para pipelines: `scripts/quality-gate.sh` e `scripts/quality-gate.ps1`;
  - Banner visual em tempo real na tela `Deploys.jsx`.

### 7. Suíte Experimental e Resultados de Desempenho (Quadro 3, Seção 3.3.2)
* **Suíte Automatizada `ArgosBenchmarkIT.java`**:
  - Três baterias experimentais completas executadas sobre PostgreSQL 15 com RLS ativo + Spring Boot 3 / Java 21:
    1. **Latência de Consulta (p50, p95, p99, Média, StdDev)** com volumes de **10.000**, **50.000** e **100.000** registros e concorrências de **1**, **8** e **16** threads;
    2. **Vazão OTLP Multi-Tenant**: Ingestão concorrente com 1, 4 e 8 organizações isoladas simultâneas;
    3. **Amostragem de Recursos**: Coleta a cada 100 ms de uso de CPU (%) e Memória Heap (MB).
* **Artefatos e Relatórios Gerados**:
  - `docs/benchmarks/benchmark-query-latency.csv`
  - `docs/benchmarks/benchmark-otlp-throughput.csv`
  - `docs/benchmarks/benchmark-resource-samples.csv`
  - `docs/benchmarks/RELATORIO_EXPERIMENTAL_QUADRO3.md`
  - Scripts executores: `scripts/run-benchmarks.sh` e `scripts/run-benchmarks.ps1`.

---

## 📊 Síntese dos Resultados Experimentais Coletados

| Experimento | Métrica Observada | Resultado Real Obtido | Critério do TCC |
|---|---|---|---|
| **Listagem Filtrada (10k reg, 1 thread)** | Latência p95 / p50 | **37.28 ms** / **20.79 ms** | SC-005 (< 2.000 ms) ✅ |
| **Listagem Filtrada (100k reg, 16 threads)** | Latência p95 / Vazão | **54.65 ms** / **309.9 req/s** | Resposta sub-segundo ✅ |
| **Métricas Incidentes (100k reg, 16 threads)** | Latência p95 / Vazão | **686.60 ms** / **29.2 req/s** | SC-005 (< 2.000 ms) ✅ |
| **Busca de Traces OTLP (100k reg, 16 threads)** | Latência p95 / Vazão | **212.30 ms** / **80.8 req/s** | Alta Concorrência ✅ |
| **Vazão Ingestão OTLP (8 tenants simultâneos)** | Spans/s e Logs/s | **4.418 spans/s** e **4.748 logs/s** | Quadro 3 Ingestão OTLP ✅ |
| **Consumo de CPU JVM durante pico de carga** | CPU Média / Pico | **5.47%** / **33.59%** | Baixo Overhead ✅ |
| **Consumo de Memória Heap JVM** | Heap Médio / Pico | **173.8 MB** / **232.7 MB** | Estabilidade de Memória ✅ |

---

## 🧪 Verificação e Testes

- [x] **Arquitetura Modular (Spring Modulith)**: `ModularityTests` aprovado (1 teste, 0 falhas).
- [x] **Isolamento Multi-Tenant**: `MultiTenantIsolationIT` aprovado (4 testes, 0 falhas).
- [x] **Deploys e Quality Gate**: `DeployIT` aprovado (8 testes, 0 falhas).
- [x] **Ingestão OTLP e Consultas**: `OtlpIngestIT` e `TelemetryQueryIT` aprovados.
- [x] **Suíte Experimental Completa**: `ArgosBenchmarkIT` aprovado com geração de CSV e Markdown.
- [x] **Frontend Quality**: `npm run lint` (ESLint 0 erros/avisos) e `npm run build` (Vite 0 erros).

---

## 🗂️ Arquivos Alterados e Criados

```text
.github/
  ├── dependabot.yml                             (Novo: auditoria de dependências)
  └── workflows/ci.yml                           (Modificado: etapas de segurança e Quality Gate)
backend/
  ├── src/main/java/br/com/argos/argos_api/
  │   ├── delivery/
  │   │   ├── DeployController.java              (Modificado: endpoint UI de Quality Gate)
  │   │   ├── DeployWebhookController.java       (Modificado: endpoint Webhook de Quality Gate)
  │   │   ├── DoraMetricsController.java         (Novo: endpoint oficial DORA)
  │   │   ├── DoraMetricsService.java            (Novo: cálculo das 4 métricas DORA)
  │   │   ├── QualityGateService.java            (Novo: lógica de bloqueio por SEV1/Alerta)
  │   │   └── dto/QualityGateResponse.java       (Novo: DTO de resposta do Quality Gate)
  │   ├── telemetry/
  │   │   ├── TelemetryController.java           (Modificado: rotas topology, red, rules)
  │   │   ├── TelemetryMonitor.java              (Modificado: monitor de SLO p95 e erro)
  │   │   └── TelemetryQueryService.java         (Modificado: queries de topologia e RED)
  │   └── shared/TenantAspect.java               (Modificado: suporte à role argos_tenant)
  ├── src/main/resources/db/migration/
  │   ├── V6_1__telemetry_rules.sql              (Novo: tabela de regras de telemetria)
  │   ├── V6_2__deploy_committed_at.sql          (Novo: suporte a committedAt em deploys)
  │   └── V7__enable_rls_all_tables.sql          (Novo: RLS FORCE em todas as tabelas)
  └── src/test/java/br/com/argos/argos_api/
      ├── MultiTenantIsolationIT.java            (Novo: suíte de isolamento PostgreSQL RLS)
      ├── benchmark/ArgosBenchmarkIT.java        (Novo: suíte experimental do Quadro 3)
      └── delivery/DeployIT.java                 (Modificado: testes de DORA e Quality Gate)
frontend/
  ├── src/components/
  │   ├── ServiceTopologyGraph.jsx               (Novo: grafo SVG de dependência de microsserviços)
  │   ├── RedCharts.jsx                          (Novo: gráficos RED com marcadores de deploy)
  │   └── IncidentsPerDay.jsx                    (Modificado: marcadores de deploy nos gráficos)
  └── src/pages/
      ├── Alerts.jsx / AlertSources.jsx          (Modificado: CRUD de regras de SLO OTLP)
      ├── Deploys.jsx                            (Modificado: banner de Quality Gate em tempo real)
      ├── IncidentDetail.jsx                     (Modificado: banner de deploy suspeito correlacionado)
      ├── Overview.jsx                           (Modificado: painel das 4 métricas DORA)
      └── Telemetry.jsx                          (Modificado: abas Topologia e RED)
docs/benchmarks/
  ├── RELATORIO_EXPERIMENTAL_QUADRO3.md          (Novo: relatório experimental sintetizado)
  ├── benchmark-query-latency.csv                (Novo: dados brutos de latência)
  ├── benchmark-otlp-throughput.csv              (Novo: dados brutos de vazão)
  └── benchmark-resource-samples.csv             (Novo: dados brutos de CPU/Memória)
scripts/
  ├── quality-gate.sh / quality-gate.ps1         (Novos: scripts CLI para pipelines)
  └── run-benchmarks.sh / run-benchmarks.ps1     (Novos: scripts executores de benchmarks)
```
