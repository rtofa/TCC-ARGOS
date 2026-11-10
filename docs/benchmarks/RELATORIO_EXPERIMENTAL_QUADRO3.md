# Relatório Experimental de Desempenho — Plataforma Argos (Quadro 3 / Seção 3.3.2)

Este relatório apresenta os resultados quantitativos coletados automaticamente pela suíte `ArgosBenchmarkIT` sobre o banco **PostgreSQL 15** (com *Row-Level Security* `FORCE ROW LEVEL SECURITY` ativo em todas as tabelas) e o backend **Spring Boot 3 / Java 21.0.10**.

## 1. Ambiente de Execução

- **Sistema Operacional**: Windows 11 (amd64)
- **Processadores lógicos disponíveis (JVM)**: 12 vCPUs
- **Runtime Java**: Java HotSpot(TM) 64-Bit Server VM 21.0.10
- **Banco de Dados**: PostgreSQL 15-alpine (Testcontainers) com índices compostos por `(organization_id, ...)` e políticas RLS ativas

## 2. Latência de Consulta por Volume e Concorrência

Avaliação da latência de resposta (em milissegundos) variando o volume armazenado (**10.000**, **50.000** e **100.000** incidentes e spans OTLP) e o nível de concorrência (**1**, **8** e **16** threads simultâneas). Todas as consultas respeitam o critério **SC-005** (`p95 < 2000 ms`).

| Endpoint | Volume | Concorrência | Mediana / p50 (ms) | p95 (ms) | p99 (ms) | Média (ms) | Desvio-Padrão (ms) | Vazão (req/s) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Listagem Filtrada de Incidentes | 10,000 | 1 | **17.84** | **30.70** | 36.58 | 19.67 | 5.08 | 50.3 |
| Métricas de Incidentes (MTTA/MTTR) | 10,000 | 1 | **25.86** | **32.34** | 35.57 | 27.35 | 3.30 | 36.3 |
| Busca de Traces OTLP | 10,000 | 1 | **13.14** | **17.07** | 17.97 | 13.91 | 1.91 | 70.6 |
| Série Temporal RED (p50/p95/p99) | 10,000 | 1 | **19.49** | **27.73** | 28.02 | 20.96 | 3.65 | 47.3 |
| Grafo de Dependência (Topology Map) | 10,000 | 1 | **80.58** | **118.39** | 128.80 | 80.62 | 27.63 | 12.4 |
| Listagem Filtrada de Incidentes | 10,000 | 8 | **25.24** | **31.35** | 32.57 | 26.25 | 3.70 | 266.6 |
| Métricas de Incidentes (MTTA/MTTR) | 10,000 | 8 | **30.36** | **40.28** | 41.30 | 32.09 | 3.46 | 233.7 |
| Busca de Traces OTLP | 10,000 | 8 | **20.48** | **27.17** | 30.68 | 21.13 | 3.11 | 325.5 |
| Série Temporal RED (p50/p95/p99) | 10,000 | 8 | **50.14** | **68.14** | 69.62 | 49.70 | 9.59 | 148.2 |
| Grafo de Dependência (Topology Map) | 10,000 | 8 | **55.46** | **92.67** | 132.43 | 61.29 | 20.67 | 100.0 |
| Listagem Filtrada de Incidentes | 10,000 | 16 | **35.23** | **54.70** | 64.15 | 38.19 | 10.85 | 334.7 |
| Métricas de Incidentes (MTTA/MTTR) | 10,000 | 16 | **57.33** | **75.14** | 92.19 | 56.93 | 14.73 | 202.4 |
| Busca de Traces OTLP | 10,000 | 16 | **28.69** | **45.48** | 46.98 | 29.60 | 8.56 | 417.0 |
| Série Temporal RED (p50/p95/p99) | 10,000 | 16 | **62.99** | **88.69** | 93.13 | 58.52 | 16.09 | 213.7 |
| Grafo de Dependência (Topology Map) | 10,000 | 16 | **85.92** | **198.38** | 210.21 | 107.65 | 46.42 | 111.3 |
| Listagem Filtrada de Incidentes | 50,000 | 1 | **19.32** | **21.92** | 22.07 | 19.11 | 1.66 | 51.8 |
| Métricas de Incidentes (MTTA/MTTR) | 50,000 | 1 | **83.18** | **109.19** | 127.44 | 85.64 | 16.75 | 11.7 |
| Busca de Traces OTLP | 50,000 | 1 | **33.50** | **46.81** | 50.31 | 35.14 | 5.20 | 28.3 |
| Série Temporal RED (p50/p95/p99) | 50,000 | 1 | **77.55** | **105.45** | 117.79 | 80.56 | 12.96 | 12.4 |
| Grafo de Dependência (Topology Map) | 50,000 | 1 | **515.16** | **706.73** | 770.45 | 533.20 | 86.31 | 1.9 |
| Listagem Filtrada de Incidentes | 50,000 | 8 | **26.10** | **28.01** | 28.99 | 25.08 | 2.42 | 308.8 |
| Métricas de Incidentes (MTTA/MTTR) | 50,000 | 8 | **93.15** | **106.40** | 112.17 | 94.96 | 8.05 | 75.1 |
| Busca de Traces OTLP | 50,000 | 8 | **58.10** | **74.34** | 76.67 | 58.70 | 9.35 | 121.1 |
| Série Temporal RED (p50/p95/p99) | 50,000 | 8 | **89.06** | **100.20** | 102.77 | 88.61 | 6.66 | 83.4 |
| Grafo de Dependência (Topology Map) | 50,000 | 8 | **248.17** | **767.98** | 906.21 | 330.50 | 208.40 | 17.2 |
| Listagem Filtrada de Incidentes | 50,000 | 16 | **89.40** | **111.02** | 112.98 | 81.27 | 23.71 | 157.1 |
| Métricas de Incidentes (MTTA/MTTR) | 50,000 | 16 | **313.29** | **445.25** | 450.20 | 291.02 | 101.50 | 42.5 |
| Busca de Traces OTLP | 50,000 | 16 | **100.02** | **156.45** | 183.91 | 110.10 | 29.44 | 115.5 |
| Série Temporal RED (p50/p95/p99) | 50,000 | 16 | **183.46** | **312.50** | 323.65 | 208.41 | 71.57 | 58.8 |
| Grafo de Dependência (Topology Map) | 50,000 | 16 | **1201.93** | **1665.51** | 1760.40 | 1090.99 | 466.68 | 10.4 |
| Listagem Filtrada de Incidentes | 100,000 | 1 | **22.37** | **29.07** | 30.14 | 22.93 | 3.25 | 43.4 |
| Métricas de Incidentes (MTTA/MTTR) | 100,000 | 1 | **143.06** | **179.48** | 197.73 | 147.52 | 18.93 | 6.8 |
| Busca de Traces OTLP | 100,000 | 1 | **62.93** | **95.76** | 97.53 | 67.57 | 12.42 | 14.8 |
| Série Temporal RED (p50/p95/p99) | 100,000 | 1 | **134.37** | **193.35** | 200.45 | 143.86 | 23.49 | 6.9 |
| Grafo de Dependência (Topology Map) | 100,000 | 1 | **1005.79** | **1295.23** | 1306.68 | 1048.26 | 125.70 | 1.0 |
| Listagem Filtrada de Incidentes | 100,000 | 8 | **27.28** | **29.33** | 29.39 | 26.10 | 2.66 | 300.0 |
| Métricas de Incidentes (MTTA/MTTR) | 100,000 | 8 | **237.45** | **251.77** | 257.30 | 237.33 | 8.96 | 33.2 |
| Busca de Traces OTLP | 100,000 | 8 | **114.45** | **154.96** | 168.41 | 117.91 | 15.76 | 59.9 |
| Série Temporal RED (p50/p95/p99) | 100,000 | 8 | **253.17** | **293.49** | 300.82 | 256.31 | 20.21 | 29.8 |
| Grafo de Dependência (Topology Map) | 100,000 | 8 | **1468.63** | **2111.15** | 2151.44 | 1423.10 | 565.70 | 4.9 |
| Listagem Filtrada de Incidentes | 100,000 | 16 | **58.51** | **89.16** | 107.61 | 61.51 | 20.69 | 206.7 |
| Métricas de Incidentes (MTTA/MTTR) | 100,000 | 16 | **398.04** | **764.10** | 796.78 | 435.42 | 168.45 | 28.3 |
| Busca de Traces OTLP | 100,000 | 16 | **235.54** | **386.50** | 395.83 | 245.96 | 69.99 | 52.3 |
| Série Temporal RED (p50/p95/p99) | 100,000 | 16 | **257.88** | **492.54** | 498.09 | 332.78 | 104.56 | 36.1 |
| Grafo de Dependência (Topology Map) | 100,000 | 16 | **2308.39** | **4083.60** | 4216.26 | 2497.63 | 981.35 | 4.9 |

## 3. Vazão de Ingestão OTLP Multi-Tenant (`spans/s` e `logs/s`)

Avaliação da capacidade de ingestão concorrente nos endpoints `/v1/traces` e `/v1/logs` com **1**, **4** e **8** organizações (tenants) simultâneas, cada uma autenticada por sua própria chave `X-API-Key` e isolada por *Row-Level Security*:

| Sinal OTLP | Tenants Simultâneos | Total de Eventos | Tempo Total (ms) | Vazão (eventos/s) | Vazão (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Desvio-Padrão (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| SPANS (/api/webhooks/otlp/v1/traces) | 1 | 500 | 539.9 | **926.0** | 37.0 | 21.27 | 31.03 | 106.21 | 22.76 |
| LOGS (/api/webhooks/otlp/v1/logs) | 1 | 500 | 283.2 | **1765.6** | 70.6 | 13.32 | 18.98 | 24.45 | 3.18 |
| SPANS (/api/webhooks/otlp/v1/traces) | 4 | 2,000 | 469.2 | **4262.2** | 170.5 | 20.97 | 28.73 | 46.71 | 5.57 |
| LOGS (/api/webhooks/otlp/v1/logs) | 4 | 2,000 | 307.3 | **6507.8** | 260.3 | 14.64 | 17.95 | 19.79 | 1.77 |
| SPANS (/api/webhooks/otlp/v1/traces) | 8 | 4,000 | 1063.4 | **3761.6** | 150.5 | 47.49 | 105.07 | 125.01 | 23.90 |
| LOGS (/api/webhooks/otlp/v1/logs) | 8 | 4,000 | 1411.5 | **2833.8** | 113.4 | 48.30 | 177.29 | 199.26 | 49.55 |

## 4. Consumo de Recursos (CPU e Memória Amostrados a cada 100 ms)

- **Total de amostras coletadas (intervalo fixo de 100 ms)**: 1084 amostras
- **Uso médio de CPU do processo JVM**: 5.65% (Pico: 45.84%)
- **Uso médio de Memória Heap JVM**: 197.7 MB (Pico: 253.7 MB / Alocado: 284.0 MB)

| Fase Experimental | Amostras | CPU Processo Média (%) | CPU Processo Pico (%) | Heap Médio (MB) | Heap Pico (MB) |
|---|---:|---:|---:|---:|---:|
| `QUERY_VOL_10000_CONC_1` | 59 | 12.45% | 26.91% | 140.8 | 172.6 |
| `QUERY_VOL_10000_CONC_8` | 13 | 16.33% | 27.46% | 152.4 | 164.7 |
| `QUERY_VOL_10000_CONC_16` | 10 | 22.96% | 25.16% | 196.5 | 210.7 |
| `SEED_VOLUME_50000` | 34 | 6.19% | 21.12% | 210.7 | 210.7 |
| `QUERY_VOL_50000_CONC_1` | 197 | 4.00% | 11.81% | 232.1 | 236.7 |
| `QUERY_VOL_50000_CONC_8` | 43 | 6.69% | 15.17% | 144.6 | 246.7 |
| `QUERY_VOL_50000_CONC_16` | 57 | 5.52% | 9.89% | 175.7 | 185.7 |
| `SEED_VOLUME_100000` | 38 | 3.20% | 5.60% | 185.7 | 185.7 |
| `QUERY_VOL_100000_CONC_1` | 362 | 3.03% | 6.79% | 208.1 | 211.7 |
| `QUERY_VOL_100000_CONC_8` | 111 | 3.46% | 6.15% | 240.2 | 245.7 |
| `QUERY_VOL_100000_CONC_16` | 114 | 4.60% | 12.81% | 136.8 | 253.7 |
| `OTLP_INGEST_SPANS_TENANTS_1` | 8 | 9.68% | 16.25% | 150.8 | 159.8 |
| `OTLP_INGEST_LOGS_TENANTS_1` | 2 | 16.25% | 16.25% | 162.8 | 163.8 |
| `OTLP_INGEST_SPANS_TENANTS_4` | 5 | 21.27% | 21.27% | 183.8 | 201.8 |
| `OTLP_INGEST_LOGS_TENANTS_4` | 4 | 38.23% | 38.23% | 215.8 | 227.8 |
| `OTLP_INGEST_SPANS_TENANTS_8` | 11 | 41.83% | 45.84% | 158.8 | 247.8 |
| `OTLP_INGEST_LOGS_TENANTS_8` | 16 | 24.49% | 45.84% | 200.6 | 223.4 |

## 5. Arquivos Brutos Gerados

- `docs/benchmarks/benchmark-query-latency.csv` — Série completa de latências (p50, p95, p99, média, desvio-padrão e req/s) por volume e concorrência.
- `docs/benchmarks/benchmark-otlp-throughput.csv` — Vazão de ingestão OTLP (`spans/s` e `logs/s`) multi-tenant.
- `docs/benchmarks/benchmark-resource-samples.csv` — Série temporal de CPU e Memória amostrada a cada 100 ms.
