# Tasks: Ingestão e consulta de telemetria (003-telemetry-ingestion)

- [x] T001 Especificação do módulo de telemetria OTLP/HTTP e ambiente de demonstração (`specs/003-telemetry-ingestion/spec.md`)
- [x] T002 Migração `V6__telemetry.sql` com tabelas `telemetry_spans`, `telemetry_logs` e `telemetry_metrics` e índices por organização
- [x] T003 Parser OTLP/JSON (`OtlpParser`) e gravação em lote idempotente (`TelemetryStore`, `TelemetryIngestService`)
- [x] T004 Endpoints de ingestão `POST /api/webhooks/otlp/v1/{traces,logs,metrics}` autenticados por `X-API-Key` (`OtlpController`)
- [x] T005 Testes unitários e de integração da ingestão e isolamento multi-tenant (`OtlpParserTest`, `OtlpIngestIT`)
- [x] T006 Serviço e controller de consulta `GET /api/telemetry/{services,traces,traces/{traceId},logs,metrics/names,metrics}` (`TelemetryQueryService`, `TelemetryController`)
- [x] T007 Testes de integração da consulta e isolamento entre organizações (`TelemetryQueryIT`)
- [x] T008 Página `/telemetry` (abas Serviços, Traces com cascata de spans e Logs) e cartão `IncidentTelemetry` no detalhe do incidente
- [x] T009 `docker-compose.yml` completo na raiz (`postgres`, `backend`, `frontend`, `seed`) e `Dockerfile` do backend e frontend
- [x] T010 Automação da demonstração com a OpenTelemetry Demo (`demo/start-demo.*`, `demo/trigger-failure.*`, `demo/otelcol-config-extras.yml.template`) e documentação (`README.md`, `demo/README.md`)
