# ARGOS — Central de Inteligência Operacional Multi-Tenant

Plataforma acadêmica de observabilidade e resposta a incidentes para arquiteturas de microsserviços, desenvolvida como Trabalho de Conclusão de Curso (2026).

O **Argos** conecta o ciclo completo de investigação de um incidente em sistemas distribuídos em uma única interface isolada por organização (*multi-tenant*):

`alerta disparado → incidente (com deduplicação) → deploys recentes → traces e logs com erro (OpenTelemetry)`

---

## Como subir tudo com um comando

Pré-requisitos: **Docker** (com Docker Compose) e **Git**.

```bash
docker compose up --build -d
```

Esse comando sobe o PostgreSQL 15, o backend Spring Boot, o console React (servido por Nginx) e executa o *seed* idempotente que cria a organização de demonstração, o usuário administrador e a chave de API do OpenTelemetry Collector em `demo/generated/argos.env`.

| Serviço | Endereço | Credenciais de demonstração |
|---|---|---|
| **Console do Argos** | http://localhost:3030 | `admin@demo.argos` / `Argos@2026!` |
| **API do Argos** | http://localhost:8081 | JWT (console) ou `X-API-Key` (webhooks/OTLP) |
| **PostgreSQL** | `localhost:5434` (`argos`) | `postgres` / `postgres` |

---

## Demonstração com microsserviços reais (OpenTelemetry Demo)

Para demonstrar o Argos investigando falhas em uma arquitetura real de microsserviços poliglota (~15 serviços instrumentados com OpenTelemetry e gerador de tráfego contínuo), suba a **Astronomy Shop (OpenTelemetry Demo)** integrada ao Argos:

**No Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -File .\demo\start-demo.ps1
```

**No Linux / macOS:**
```bash
./demo/start-demo.sh
```

O script clona a tag `3.1.0` da OpenTelemetry Demo (em `demo/opentelemetry-demo/`), injeta a chave de API gerada pelo *seed* no OpenTelemetry Collector (`otelcol-config-extras.yml`) e inicia todos os microsserviços enviando traces, logs e métricas via OTLP/HTTP para o Argos.

### Roteiro de apresentação (5 minutos)

1. **Operação normal e visibilidade dos microsserviços**
   - Abra http://localhost:3030 e entre com `admin@demo.argos` / `Argos@2026!`.
   - Acesse **Telemetria** (`/telemetry`): mostre a lista de microsserviços ativos (`frontend`, `cart`, `checkout`, `product-catalog`, `payment`, `recommendation`, `ad`, etc.), os traces distribuídos em cascata (*waterfall*) e os logs correlacionados por `traceId`.
2. **Injeção de falha controlada + abertura automática de incidente**
   - Execute o script que ativa uma *feature flag* de falha no `flagd` da loja e dispara o webhook de alerta para o Argos:
     ```powershell
     # Windows
     powershell -ExecutionPolicy Bypass -File .\demo\trigger-failure.ps1 -Flag productCatalogFailure
     ```
     ```bash
     # Linux / macOS
     ./demo/trigger-failure.sh productCatalogFailure
     ```
     *(Flags disponíveis: `cartFailure`, `productCatalogFailure`, `paymentFailure`)*
3. **Investigação no contexto do incidente**
   - No console do Argos, abra **Incidentes** (`/incidents`) e clique no incidente recém-aberto (`Via alerta`).
   - Na tela do incidente, mostre em uma única visão:
     - **Telemetria do incidente**: traces com erro e logs de erro filtrados automaticamente pelos serviços afetados e pela janela de tempo do incidente, com clique direto para a cascata de spans (*waterfall*).
     - **O que mudou?**: deploys recentes registrados por pipelines CI/CD para os serviços afetados antes da abertura do incidente.
     - **Alertas recebidos** e **Linha do tempo** auditável.
4. **Mitigação e resolução automática**
   - Desative a falha e envie o evento de resolução:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\demo\trigger-failure.ps1 -Flag productCatalogFailure -Reset
     ```
   - Mostre o incidente atualizado/resolvido e o cálculo automático de **MTTA** e **MTTR** em **Visão geral** (`/overview`).

Veja mais detalhes em [`demo/README.md`](demo/README.md).

---

## Arquitetura e pilha tecnológica

- **Backend (`backend/`)**: Java 17, Spring Boot 4.1.1, Spring Modulith 2.1.1, Spring Data JPA + JDBC em lote, Flyway, Spring Security (OAuth2 Resource Server com JWT para usuários e filtro `X-API-Key` para ingestão em `/api/webhooks/**`).
  - Módulos verificados por `ModularityTests`:
    - `identity`: organizações, usuários, autenticação JWT e papéis (`ADMIN`, `EDITOR`, `VIEWER`, `EXECUTIVE`).
    - `incident`: ciclo de vida de incidentes, linha do tempo, comentários e métricas operacionais (MTTA/MTTR).
    - `apikey`: emissão, listagem e revogação de chaves de API com hash SHA-256.
    - `alerting`: fontes de alerta configuráveis com JSONPath e webhook com deduplicação por `dedupKey`.
    - `delivery`: webhook de registro de deploys por pipelines de CI/CD e correlação com incidentes.
    - `telemetry`: receptor OTLP/HTTP JSON (`/api/webhooks/otlp/v1/{traces,logs,metrics}`) e API de consulta (`/api/telemetry/**`).
    - `shared`: contexto multi-tenant (`TenantFilter`, `TenantContext`, `TenantAspect`), usuário autenticado e relógio.
- **Frontend (`frontend/`)**: React 19.2, React Router 7, Vite 8.

## Executando os testes

```bash
# Backend (testes unitários, modularidade e integração com PostgreSQL via Testcontainers)
cd backend && ./mvnw verify

# Frontend (lint e build de produção)
cd frontend && npm ci && npm run lint && npm run build
```
