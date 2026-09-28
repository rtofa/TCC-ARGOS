---

description: "Lista de tarefas da feature Chaves de API e Webhook de Alertas (BASE-03 + INT-03)"
---

# Tasks: Chaves de API e Webhook de Alertas (BASE-03 + INT-03)

**Input**: Design documents from `specs/002-alert-webhook-api-keys/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/)

**Tests**: OBRIGATÓRIOS (constitution, Princípio III): unidade JUnit 5 + Mockito; integração
Spring Boot Test + MockMvc + Postgres (Testcontainers). Escrever os testes de cada história antes
da implementação e vê-los falhar.

**Organization**: por user story. Abreviações:
- `MAIN` = `backend/src/main/java/br/com/argos/argos_api`
- `TEST` = `backend/src/test/java/br/com/argos/argos_api`

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 Adicionar `com.jayway.jsonpath:json-path` (escopo compile, sem `<version>`) ao `backend/pom.xml` e validar com `.\mvnw.cmd -q dependency:resolve`
- [X] T002 Extrair a infraestrutura comum de testes de integração: criar `TEST/support/TestFixtures.java` (público; métodos `createOrganization`, `createUser`, `token(userId, orgId, role)`, `cleanDatabase` — este apagando, nesta ordem, `alert_incident_links`, `alert_sources`, `incident_events`, `incidents`, `api_keys`, `users`, `organizations`) e `TEST/support/AbstractIntegrationTest.java` (público; `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`, orgs A/B e usuários ADMIN/EDITOR/VIEWER/EXECUTIVE em A e ADMIN em B, métodos `asAdminA()` etc.); fazer `TEST/incident/AbstractIncidentIT.java` estender `AbstractIntegrationTest` e `TEST/incident/IncidentTestSupport.java` delegar para `TestFixtures`, sem alterar os testes do MON-10; rodar `.\mvnw.cmd test` e confirmar os 65 testes verdes

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: nenhuma história começa antes desta fase

- [X] T003 Criar `backend/src/main/resources/db/migration/V4__api_keys_and_alerting.sql` conforme [data-model.md](data-model.md): tabela `api_keys` (`name VARCHAR(100) NOT NULL`, `prefix VARCHAR(20) NOT NULL`, `key_hash VARCHAR(64) NOT NULL UNIQUE`, `created_by UUID NOT NULL REFERENCES users`, `created_at TIMESTAMPTZ NOT NULL`, `last_used_at`, `revoked_at` `TIMESTAMPTZ NULL`, índice `(organization_id, created_at DESC)`); tabela `alert_sources` (caminhos `VARCHAR(200)`, `title_path` e `dedup_key_path` NOT NULL, `status_map`/`severity_map JSONB NOT NULL DEFAULT '{}'`, `default_severity VARCHAR(10) NOT NULL DEFAULT 'SEV3' CHECK IN ('SEV1','SEV2','SEV3','SEV4')`, `created_by`, `created_at`, `updated_at`, `deleted_at`); tabela `alert_incident_links` (`source_id UUID NULL REFERENCES alert_sources`, `dedup_key VARCHAR(200) NOT NULL`, `incident_id UUID NOT NULL REFERENCES incidents`, `occurrences INTEGER NOT NULL DEFAULT 1`, `first_seen_at`, `last_seen_at` NOT NULL, `closed_at` NULL) com índice único parcial `(organization_id, COALESCE(source_id, '00000000-0000-0000-0000-000000000000'::uuid), dedup_key) WHERE closed_at IS NULL` e índice `(incident_id)`; alterações no MON-10: `ALTER TABLE incidents ALTER COLUMN created_by DROP NOT NULL` + `CHECK (source = 'ALERT' OR created_by IS NOT NULL)`, `ALTER TABLE incident_events ALTER COLUMN actor_id DROP NOT NULL`, `ADD COLUMN api_key_id UUID NULL REFERENCES api_keys(id)`. Todas as tabelas novas com `organization_id UUID NOT NULL REFERENCES organizations`
- [X] T004 [P] Acrescentar em `TEST/incident/domain/IncidentTest.java` testes (JUnit puro) de: `Incident.openFromAlert(...)` → `source=ALERT`, `createdBy=null`, `status=OPEN`, evento `ALERT_TRIGGERED` com `actorId=null`, `apiKeyId`, `newValue`=dedupKey e `body`=nome da fonte; `resolveByAlert(apiKeyId, now)` em incidente não resolvido → `RESOLVED`, `resolvedAt`, reconhecimento preenchido, um evento `ALERT_RESOLVED` (old = status anterior, new = `RESOLVED`); `resolveByAlert` em incidente já resolvido → nenhum evento; `changeSeverity` com ator nulo gera evento com `actorId=null`
- [X] T005 Ajustar o domínio do MON-10 para autor "sistema" (fazer T004 passar): `MAIN/incident/domain/EventType.java` + `ALERT_TRIGGERED`, `ALERT_RESOLVED`; `MAIN/incident/domain/IncidentEvent.java` com `actorId` anulável, novo campo `apiKeyId` (`api_key_id`, `updatable=false`) e fábricas `alertTriggered(incident, apiKeyId, at, dedupKey, sourceName)`, `alertResolved(incident, apiKeyId, at, from)`, `severityChanged` aceitando ator nulo e `apiKeyId`; `MAIN/incident/domain/Incident.java` com `createdBy` anulável, fábrica `openFromAlert(orgId, title, description, severity, services, now)` e método `List<IncidentEvent> resolveByAlert(UUID apiKeyId, Instant now)`
- [X] T006 [P] Atualizar `MAIN/incident/dto/IncidentEventResponse.java` com `String actorType` (`SYSTEM` quando `actorId` nulo, senão `USER`) e `UUID apiKeyId`; atualizar `specs/001-incident-management/contracts/incidents-api.md` com os campos aditivos
- [X] T007 Rodar `.\mvnw.cmd test` (suíte do MON-10 + T004 verdes, `ModularityTests` verde)

**Checkpoint**: MON-10 intacto e pronto para incidentes automáticos

---

## Phase 3: User Story 1 - Gerenciar chaves de API (Priority: P1) 🎯 MVP

**Goal**: ADMIN cria, lista e revoga chaves; a chave autentica sistemas externos só em `/api/webhooks/**`.

**Independent Test**: criar chave, ver o valor só uma vez, validar com `GET /api/webhooks/ping`,
ver o "último uso", revogar e confirmar 401.

### Tests for User Story 1 ⚠️

- [X] T008 [P] [US1] Teste de unidade `TEST/apikey/domain/ApiKeyGeneratorTest.java`: chave começa com `argos_`, tem 49 caracteres, é URL-safe; duas chaves geradas são diferentes; `prefix` = 12 primeiros caracteres; `hash(chave)` é SHA-256 hex de 64 caracteres, determinístico e diferente da chave
- [X] T009 [P] [US1] Teste Mockito `TEST/apikey/ApiKeyServiceTest.java` (mocks `ApiKeyRepository`, `CurrentUser`; `Clock.fixed`): `create` persiste só `key_hash` + `prefix` (a entidade salva não contém o valor completo) e devolve o valor completo uma vez, com organização e criador do usuário autenticado; `list` só da organização; `revoke` define `revokedAt` e não altera uma chave já revogada; `revoke` de chave de outra organização → `ApiKeyNotFoundException`; `authenticate(valor)` retorna `ApiKeyPrincipal` para chave ativa e vazio para inexistente ou revogada, chamando `touchLastUsed`
- [X] T010 [P] [US1] Teste de integração `TEST/apikey/ApiKeyIT.java` (extends `AbstractIntegrationTest`): POST 201 com `key` e `prefix`; GET lista sem `key` e com `status=ACTIVE`; `GET /api/webhooks/ping` com `X-API-Key` e com `Authorization: Bearer` → 200 com `organizationId` de A e `keyPrefix`, e `lastUsedAt` preenchido na listagem; revogar → `REVOKED`, `revokedAt`, e ping → 401; ping sem chave ou com chave inventada → 401; ping com JWT de ADMIN → 401; chave de API em `GET /api/incidents` → 401; EDITOR/VIEWER/EXECUTIVE em POST/GET/revoke → 403; ADMIN de B não vê chaves de A e revoke de chave de A → 404; `name` vazio ou com 101 caracteres → 400; conferir via `JdbcTemplate` que nenhuma coluna de `api_keys` contém o valor completo

### Implementation for User Story 1

- [X] T011 [P] [US1] Criar `MAIN/apikey/domain/ApiKeyGenerator.java` (`SecureRandom`, 32 bytes, Base64 URL sem padding, prefixo `argos_`; `static String hash(String)` SHA-256 hex; `static String prefixOf(String)`)
- [X] T012 [P] [US1] Criar `MAIN/apikey/domain/ApiKey.java` (entidade mapeando `api_keys`, sem setters públicos, `static ApiKey create(orgId, name, prefix, keyHash, createdBy, now)`, `boolean isActive()`, `void revoke(Instant now)` idempotente) e `MAIN/apikey/domain/ApiKeyRepository.java` (`findByKeyHash`, `findByIdAndOrganizationId`, `findByOrganizationIdOrderByCreatedAtDesc`, `@Modifying` `touchLastUsed(id, now, threshold)` conforme [research.md R3](research.md#r3-último-uso-sem-escrita-a-cada-requisição))
- [X] T013 [P] [US1] Criar a API pública `MAIN/apikey/ApiKeyPrincipal.java` (record `UUID keyId, UUID organizationId, String prefix`), `MAIN/apikey/ApiKeyNotFoundException.java` e DTOs em `MAIN/apikey/dto/`: `CreateApiKeyRequest` (`@NotBlank @Size(max=100) name`), `CreatedApiKeyResponse` (`id, name, prefix, key, createdAt`), `ApiKeyResponse` (`id, name, prefix, createdBy, createdAt, lastUsedAt, status, revokedAt`)
- [X] T014 [US1] Implementar `MAIN/apikey/ApiKeyService.java`: `create`, `list`, `revoke` (tenant via `CurrentUser`, horários truncados em microssegundos) e `Optional<ApiKeyPrincipal> authenticate(String rawKey)` (hash → busca → ativa → `touchLastUsed`)
- [X] T015 [US1] Implementar `MAIN/apikey/ApiKeyController.java` (`POST /api/api-keys` 201, `GET /api/api-keys`, `POST /api/api-keys/{id}/revoke`, todos `@PreAuthorize("hasRole('ADMIN')")`) e `MAIN/apikey/ApiKeyExceptionHandler.java` (`ProblemDetail`: 404 para `ApiKeyNotFoundException`, 400 para validação/JSON inválido), conforme [contracts/api-keys.md](contracts/api-keys.md)
- [X] T016 [US1] Implementar em `MAIN/apikey/security/`: `ApiKeyAuthentication` (`AbstractAuthenticationToken` com `ApiKeyPrincipal` e autoridade `ROLE_INGEST`), `ApiKeyAuthenticationFilter` (`OncePerRequestFilter`; lê `X-API-Key` ou `Authorization: Bearer argos_...`; válido → autentica e define `TenantContext`; inválido/ausente → 401 `application/problem+json`; limpa `TenantContext` no `finally`) e `ApiKeySecurityConfig` (`@Order(1)` `SecurityFilterChain` com `securityMatcher("/api/webhooks/**")`, stateless, CSRF desabilitado, filtro antes de `AnonymousAuthenticationFilter`, `anyRequest().hasRole("INGEST")`, sem `oauth2ResourceServer`)
- [X] T017 [US1] Criar `MAIN/apikey/ApiKeyPingController.java` com `GET /api/webhooks/ping` → `{ "organizationId": ..., "keyPrefix": ... }` a partir do `ApiKeyPrincipal` autenticado; documentar a rota em [contracts/api-keys.md](contracts/api-keys.md)
- [X] T018 [US1] Rodar `.\mvnw.cmd test` até T008–T010 passarem sem quebrar o MON-10

**Checkpoint**: chaves funcionando e isoladas das demais rotas (MVP)

---

## Phase 4: User Story 2 - Alerta no formato padrão abre e resolve incidente (Priority: P1)

**Goal**: `POST /api/webhooks/alerts` abre, atualiza, resolve ou ignora incidentes com deduplicação segura.

**Independent Test**: disparado → repetido → resolvido → disparado de novo, com a mesma dedupKey,
e 20 envios simultâneos resultando em 1 incidente.

### Tests for User Story 2 ⚠️

- [X] T019 [P] [US2] Teste Mockito `TEST/incident/AutomatedIncidentsTest.java` (mocks dos repositórios de incidente/evento; `Clock.fixed`): `openFromAlert` salva incidente `ALERT` na organização informada com severidade convertida de `"SEV1"` e evento `ALERT_TRIGGERED`; `isResolved` de incidente de outra organização → `true` (tratado como inexistente/fechado); `updateSeverityFromAlert` com severidade igual não salva evento e diferente salva `SEVERITY_CHANGED` com ator nulo; `resolveFromAlert` retorna `true` e salva `ALERT_RESOLVED` quando havia algo a resolver e `false` caso contrário; severidade inválida → `IllegalArgumentException`
- [X] T020 [P] [US2] Teste de unidade `TEST/alerting/mapping/AlertMapperTest.java` (formato padrão): alerta único; `{"alerts":[...]}` com 3 itens; `critical`/`high`/`warning`/`info`/`SEV2`/`Critical` traduzidos; severidade ausente ou desconhecida → SEV3; status ausente → `firing`; status `resolved`; status desconhecido → item recusado com motivo; `service` texto e lista; `title` ou `dedupKey` ausentes → item recusado com motivo e demais itens preservados; mais de 100 itens → `IllegalArgumentException`; JSON inválido → `IllegalArgumentException`
- [X] T021 [P] [US2] Teste Mockito `TEST/alerting/AlertProcessorTest.java` (mocks `AutomatedIncidents`, `AlertIncidentLinkRepository`; `TransactionTemplate` que executa direto): cobre cada linha da tabela "Decisão por alerta" do [data-model.md](data-model.md) (OPENED, UPDATED com e sem mudança de severidade, OPENED após incidente resolvido por pessoa fechando o vínculo antigo, RESOLVED, IGNORED ×2, REJECTED), verifica a aquisição do advisory lock antes da busca do vínculo e que uma exceção em um alerta não impede os seguintes (resultado `REJECTED` com motivo genérico)
- [X] T022 [P] [US2] Teste de integração `TEST/alerting/AlertWebhookIT.java` (extends `AbstractIntegrationTest`; chave criada via `POST /api/api-keys` como ADMIN de A e outra de B): fluxo firing → firing ×3 → resolved → firing (ações `OPENED`, `UPDATED`, `RESOLVED`, `OPENED`; 2 incidentes no total, o primeiro `RESOLVED` com `mttrSeconds`, `source=ALERT`, `createdBy=null`; timeline com `ALERT_TRIGGERED` e `ALERT_RESOLVED` com `actorType=SYSTEM` e `apiKeyId`); `GET /api/incidents/{id}/alerts` com `occurrences=4` (JWT, VIEWER → 200; incidente manual → `[]`); severidade alterada em repetição gera `SEVERITY_CHANGED`; alerta resolvido sem incidente → `IGNORED`; incidente resolvido manualmente + alerta resolvido → `IGNORED` e novo firing → `OPENED`; envio com 3 alertas sendo 1 sem `dedupKey` → 200 com `accepted=2`, `rejected=1`; corpo vazio / JSON inválido / 101 alertas → 400; corpo > 1 MB → 413; mesma dedupKey em A e B → um incidente em cada organização; incidente manual existente não é tocado; **100 envios simultâneos** (`ExecutorService` com 20 threads) do mesmo alerta → exatamente 1 incidente e `occurrences=100`; alerta com `"organizationId"` de B no corpo enviado com a chave de A → incidente criado em A (FR-006); `GET /api/incidents/{id}/alerts` de incidente de A com token de B → `[]`; chave revogada → 401 e nada criado; JWT no webhook → 401

### Implementation for User Story 2

- [X] T023 [US2] Criar a API pública `MAIN/incident/AutomatedIncidents.java` (`@Service`, transações do chamador): `UUID openFromAlert(UUID orgId, UUID apiKeyId, String title, String description, String severity, List<String> services, String dedupKey, String sourceName)`, `boolean isResolved(UUID orgId, UUID incidentId)`, `void updateSeverityFromAlert(UUID orgId, UUID incidentId, String severity, UUID apiKeyId)`, `boolean resolveFromAlert(UUID orgId, UUID incidentId, UUID apiKeyId)`; título truncado em 200 caracteres
- [X] T024 [P] [US2] Criar em `MAIN/alerting/mapping/`: `AlertMapping` (record com `itemsPath`, caminhos, `statusMap`, `severityMap`, `defaultSeverity`), `MappedAlert` (record `index, title, dedupKey, status (FIRING/RESOLVED), severity ("SEV1".."SEV4"), services, description, rejectionReason`), `ArgosDefaultMapping` (constante conforme [data-model.md](data-model.md)) e `AlertMapper` (`List<MappedAlert> map(String body, AlertMapping)` com Jayway JsonPath: `itemsPath` opcional — no formato padrão usar `$.alerts` somente quando existir —, limite de 100, traduções sem diferenciar maiúsculas, truncamento de `dedupKey` em 200 caracteres)
- [X] T025 [P] [US2] Criar `MAIN/alerting/domain/AlertIncidentLink.java` (entidade de `alert_incident_links`, `static open(orgId, sourceId, dedupKey, incidentId, now)`, `registerOccurrence(now)`, `close(now)`) e `MAIN/alerting/domain/AlertIncidentLinkRepository.java` (`findOpen(orgId, sourceId, dedupKey)` tratando `sourceId` nulo; `findByIncidentIdAndOrganizationIdOrderByFirstSeenAt`; `@Query(nativeQuery)` `lockDedupKey(String lockKey)` executando `SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))`)
- [X] T026 [US2] Implementar `MAIN/alerting/AlertProcessor.java`: `WebhookResult process(ApiKeyPrincipal, UUID sourceIdOrNull, String sourceName, List<MappedAlert>)`, cada alerta em `TransactionTemplate` própria, lock `org:source:dedupKey`, decisão conforme a tabela do [data-model.md](data-model.md) usando `AutomatedIncidents`; exceção inesperada em um alerta → `REJECTED` ("erro ao processar alerta") sem interromper os demais
- [X] T027 [P] [US2] Criar DTOs `MAIN/alerting/dto/WebhookResult.java` (`received, accepted, rejected, results[]`), `AlertResult` (`index, dedupKey, action, incidentId, reason`, nulos omitidos) e `IncidentAlertLinkResponse` (`sourceId, sourceName, dedupKey, occurrences, firstSeenAt, lastSeenAt, closedAt`)
- [X] T028 [US2] Implementar `MAIN/alerting/AlertWebhookController.java` com `POST /api/webhooks/alerts` (corpo `String`; > 1 MB → 413; vazio/JSON inválido/> 100 → 400; `ApiKeyPrincipal` do `@AuthenticationPrincipal`) e `MAIN/alerting/AlertingExceptionHandler.java` (`ProblemDetail` 400/404/413, restrito ao pacote `alerting`); configurar `spring.servlet.multipart`/limite de corpo se necessário para permitir ler até 1 MB + 1 byte
- [X] T029 [US2] Implementar `MAIN/alerting/IncidentAlertsController.java` com `GET /api/incidents/{id}/alerts` (JWT, papéis de leitura, organização do `CurrentUser`; `sourceName` = nome da fonte ou "Formato padrão")
- [X] T030 [US2] Rodar `.\mvnw.cmd test` até T019–T022 passarem sem quebrar US1 e MON-10

**Checkpoint**: incidente automático funcionando no formato padrão

---

## Phase 5: User Story 3 - Fontes de alerta com mapeamento configurável (Priority: P2)

**Goal**: cadastrar fontes com regras JSONPath e receber alertas em formatos de terceiros (ex.: Grafana).

**Independent Test**: cadastrar a fonte "Grafana" e enviar `grafana-webhook.json` para o endereço dela.

### Tests for User Story 3 ⚠️

- [X] T031 [P] [US3] Criar `backend/src/test/resources/alerting/grafana-webhook.json` com um payload real do webhook do Grafana (unified alerting): `receiver`, `status`, e `alerts` com 2 itens — um `firing` (`labels.alertname`, `labels.severity=critical`, `labels.service=checkout-api`, `annotations.summary`, `annotations.description`, `fingerprint`) e um `resolved` (`labels.severity=warning`, outro `fingerprint`)
- [X] T032 [P] [US3] Acrescentar em `TEST/alerting/mapping/AlertMapperTest.java` o caso Grafana: com as regras de [contracts/alert-sources.md](contracts/alert-sources.md), o arquivo de T031 gera 2 `MappedAlert` com título, dedupKey (`fingerprint`), status, severidade (`critical`→SEV1, `warning`→SEV3), serviço e descrição corretos; `severityMap` sem o valor → `defaultSeverity`
- [X] T033 [P] [US3] Teste Mockito `TEST/alerting/AlertSourceServiceTest.java` (mocks `AlertSourceRepository`, `CurrentUser`): cria com organização/criador do usuário; recusa caminho JSONPath inválido, `titlePath`/`dedupKeyPath` ausentes, valor de `severityMap` fora de SEV1–SEV4 e de `statusMap` fora de `firing`/`resolved`; `update` e `delete` de fonte de outra organização → `AlertSourceNotFoundException`; `delete` define `deletedAt`; `toMapping()` converte a entidade em `AlertMapping`
- [X] T034 [P] [US3] Teste de integração `TEST/alerting/AlertSourceIT.java`: CRUD completo com `webhookPath`; VIEWER/EXECUTIVE → 403 em POST/PUT/DELETE e 200 em GET; ADMIN de B → 404 nas fontes de A; validações → 400; enviar `grafana-webhook.json` ao `webhookPath` com a chave de A → `results` com `OPENED` para o firing e `IGNORED` para o resolved, incidente SEV1 com serviço `checkout-api`; reenviar com os dois `resolved` → `RESOLVED`; `GET /api/incidents/{id}/alerts` mostra `sourceName="Grafana"`; mesma dedupKey no formato padrão e na fonte → incidentes distintos; webhook de fonte de B com chave de A → 404; fonte removida → 404 no webhook e 404 no GET, e os incidentes já criados por ela continuam existindo

### Implementation for User Story 3

- [X] T035 [P] [US3] Criar `MAIN/alerting/domain/JsonMapConverter.java` (`AttributeConverter<Map<String,String>, String>` com `tools.jackson.databind.json.JsonMapper`, coluna `jsonb` via `@ColumnTransformer(write = "?::jsonb")`), `MAIN/alerting/domain/AlertSource.java` (entidade de `alert_sources`, `create`, `update`, `delete(now)`, `AlertMapping toMapping()`) e `MAIN/alerting/domain/AlertSourceRepository.java` (`findByIdAndOrganizationIdAndDeletedAtIsNull`, `findByOrganizationIdAndDeletedAtIsNullOrderByName`)
- [X] T036 [P] [US3] Criar DTOs `MAIN/alerting/dto/AlertSourceRequest.java` (`@NotBlank @Size(max=100) name`; caminhos `@Size(max=200)`; `titlePath`/`dedupKeyPath` `@NotBlank`; mapas; `defaultSeverity`) e `AlertSourceResponse.java` (inclui `webhookPath`), e `MAIN/alerting/AlertSourceNotFoundException.java`
- [X] T037 [US3] Implementar `MAIN/alerting/AlertSourceService.java` (CRUD com tenant do `CurrentUser`, validação com `JsonPath.compile` e dos mapas, remoção lógica) e `MAIN/alerting/AlertSourceController.java` (`/api/alert-sources`, leitura para todos os papéis e escrita ADMIN/EDITOR), conforme [contracts/alert-sources.md](contracts/alert-sources.md)
- [X] T038 [US3] Adicionar `POST /api/webhooks/alerts/{sourceId}` em `MAIN/alerting/AlertWebhookController.java`: carrega a fonte ativa da organização da chave (senão 404), mapeia com `toMapping()` e processa com `sourceId` e `sourceName`
- [X] T039 [US3] Rodar `.\mvnw.cmd test` até T032–T034 passarem sem quebrar US1, US2 e MON-10

**Checkpoint**: todas as histórias funcionando

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T040 Rodar a suíte completa `.\mvnw.cmd test` (MON-10 + 002 + `ModularityTests`) e corrigir falhas
- [X] T041 Subir a API com o banco de dev (`docker compose up -d` + `.\mvnw.cmd spring-boot:run`), confirmar Flyway `V4` sem erro com `ddl-auto=validate` e executar os passos 1–5 do [quickstart.md](quickstart.md) via curl
- [ ] T042 Executar o roteiro manual completo do [quickstart.md](quickstart.md) (passos 1–7) — **validação feita pelo desenvolvedor**
- [X] T043 [P] Atualizar `Status` de [spec.md](spec.md) e registrar em [plan.md](plan.md) os desvios ocorridos na implementação

---

## Dependencies & Execution Order

- **Setup (1)** → **Fundação (2)** → histórias.
- **US1** depende da Fundação.
- **US2** depende de US1 (autenticação por chave) e da Fundação (autor sistema).
- **US3** depende de US2 (processador e controller de webhook).
- **Polish** depende das histórias.

### Parallel Opportunities

- Fundação: T004 e T006 em paralelo (após T003).
- US1: T008–T010 (testes) em paralelo; T011–T013 em paralelo.
- US2: T019–T022 em paralelo; T024, T025 e T027 em paralelo após T023.
- US3: T031–T034 em paralelo; T035 e T036 em paralelo.

## Parallel Example: User Story 2

```text
Task: "T019 AutomatedIncidentsTest em TEST/incident/AutomatedIncidentsTest.java"
Task: "T020 AlertMapperTest em TEST/alerting/mapping/AlertMapperTest.java"
Task: "T021 AlertProcessorTest em TEST/alerting/AlertProcessorTest.java"
Task: "T022 AlertWebhookIT em TEST/alerting/AlertWebhookIT.java"
```

## Implementation Strategy

1. Setup + Fundação → MON-10 continua verde.
2. US1 (chaves) → validar com `/api/webhooks/ping`.
3. US2 (formato padrão) → **demo principal: incidente abre e fecha sozinho**.
4. US3 (fontes) → Grafana real.
5. Commit por história citando `BASE-03`/`INT-03`.

## Notes

- Não implementar rate limit (BASE-04), notificações (MON-08) nem correlação de alertas diferentes (MON-09).
- Chaves só valem em `/api/webhooks/**`; qualquer desvio disso é bug de segurança.
