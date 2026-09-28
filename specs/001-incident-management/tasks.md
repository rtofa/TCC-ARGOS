---

description: "Lista de tarefas da feature Gestão de Incidentes (MON-10)"
---

# Tasks: Gestão de Incidentes (MON-10)

**Input**: Design documents from `specs/001-incident-management/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/incidents-api.md](contracts/incidents-api.md)

**Tests**: OBRIGATÓRIOS pela constitution (Princípio III): unidade com JUnit 5 + Mockito,
integração com Spring Boot Test + MockMvc + Postgres (Testcontainers). Escrever os testes de
cada história ANTES da implementação e confirmar que falham.

**Organization**: tarefas agrupadas por user story; cada história é testável isoladamente.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência pendente)
- **[Story]**: história do [spec.md](spec.md) (US1–US4)
- Caminhos relativos à raiz do repositório. Abreviações usadas abaixo:
  - `MAIN` = `backend/src/main/java/br/com/argos/argos_api`
  - `TEST` = `backend/src/test/java/br/com/argos/argos_api`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: dependências e infraestrutura de testes

- [X] T001 Adicionar ao `backend/pom.xml` (versões geridas pelo Spring Boot 4, sem `<version>`): `spring-boot-starter-validation` (compile); e em escopo `test`: `spring-boot-starter-security-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`, `org.testcontainers:testcontainers-junit-jupiter`. Se algum artefato não resolver com esse nome no Boot 4, usar o equivalente gerido pelo BOM (ex.: `spring-security-test`, `org.testcontainers:postgresql`) e registrar a troca. Validar com `.\mvnw.cmd -q dependency:resolve` em `backend/`
- [X] T002 [P] Criar `TEST/TestcontainersConfiguration.java`: `@TestConfiguration(proxyBeanMethods = false)` com bean `PostgreSQLContainer` (imagem `postgres:15`) anotado `@ServiceConnection`, para que Flyway aplique as migrations reais nos testes
- [X] T003 [P] Criar `TEST/ModularityTests.java` com `ApplicationModules.of(ArgosApiApplication.class).verify()` (JUnit puro, sem Spring context)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: esquema, entidades base e componentes compartilhados usados por todas as histórias

**⚠️ CRITICAL**: nenhuma história começa antes desta fase terminar

- [X] T004 Corrigir `MAIN/shared/tenant/TenantAspect.java`: substituir `SET LOCAL app.current_tenant = :tenantId` + `executeUpdate()` por `SELECT set_config('app.current_tenant', :tenantId, true)` + `getSingleResult()`, passando `''` quando o tenant for nulo (ver [research.md R2](research.md#r2-correção-mínima-do-tenantaspect))
- [X] T005 [P] Criar `MAIN/shared/security/AuthenticatedUser.java` (record `UUID userId, UUID organizationId`) e `MAIN/shared/security/CurrentUser.java` (`@Component` com `AuthenticatedUser get()` que lê `sub` e `tenant_id` do `JwtAuthenticationToken` no `SecurityContextHolder`; lança `AccessDeniedException` se ausente); criar também `MAIN/shared/package-info.java` com `@org.springframework.modulith.ApplicationModule(type = ApplicationModule.Type.OPEN)` para que os subpacotes de `shared` (infraestrutura transversal) possam ser usados por outros módulos sem violar `ModularityTests`
- [X] T006 [P] Criar a API pública `MAIN/identity/OrganizationMembers.java` (interface com `boolean isMember(UUID organizationId, UUID userId)`) e a implementação `MAIN/identity/internal/OrganizationMembersImpl.java` (`@Service` usando `UserRepository.findById` e comparando `organizationId`)
- [X] T007 [P] Criar `MAIN/shared/time/ClockConfig.java` com `@Bean Clock clock()` retornando `Clock.systemUTC()`
- [X] T008 Criar a migration `backend/src/main/resources/db/migration/V3__init_incidents.sql` exatamente como [data-model.md](data-model.md): tabela `incidents` (`title VARCHAR(200) NOT NULL`, `description TEXT NULL`, `severity VARCHAR(10) NOT NULL CHECK IN ('SEV1','SEV2','SEV3','SEV4')`, `status VARCHAR(20) NOT NULL CHECK IN ('OPEN','INVESTIGATING','MITIGATED','RESOLVED')`, `source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' CHECK IN ('MANUAL','ALERT')`, `affected_services TEXT[] NOT NULL DEFAULT '{}'`, `assignee_id UUID NULL REFERENCES users`, `created_by UUID NOT NULL REFERENCES users`, `opened_at`, `acknowledged_at`, `resolved_at`, `updated_at` como `TIMESTAMPTZ`, `organization_id UUID NOT NULL REFERENCES organizations`) com índices `(organization_id, opened_at DESC)` e `(organization_id, status)`; tabela `incident_events` (`seq BIGINT GENERATED ALWAYS AS IDENTITY`, `type VARCHAR(30) NOT NULL`, `actor_id UUID NOT NULL REFERENCES users`, `occurred_at TIMESTAMPTZ NOT NULL`, `old_value VARCHAR(100) NULL`, `new_value VARCHAR(100) NULL`, `body TEXT NULL`, `incident_id` e `organization_id` NOT NULL com FKs) com índice `(incident_id, occurred_at, seq)`
- [X] T009 [P] Criar enums em `MAIN/incident/domain/`: `Severity.java` (SEV1–SEV4), `IncidentStatus.java` (OPEN, INVESTIGATING, MITIGATED, RESOLVED), `IncidentSource.java` (MANUAL, ALERT), `EventType.java` (OPENED, STATUS_CHANGED, REOPENED, SEVERITY_CHANGED, ASSIGNEE_CHANGED, COMMENT)
- [X] T010 Criar a entidade JPA `MAIN/incident/domain/IncidentEvent.java` mapeando `incident_events` (enums como `@Enumerated(STRING)`, `seq` com `insertable=false, updatable=false`, `Instant` para horários), sem setters públicos, com fábricas estáticas `opened(...)`, `statusChanged(...)`, `reopened(...)`, `severityChanged(...)`, `assigneeChanged(...)`, `comment(...)`
- [X] T011 Criar a entidade JPA `MAIN/incident/domain/Incident.java` mapeando `incidents` (`affected_services` como `List<String>` com `@JdbcTypeCode(SqlTypes.ARRAY)`), com fábrica `static Incident open(UUID orgId, UUID createdBy, String title, String description, Severity severity, List<String> affectedServices, UUID assigneeId, Instant now)` que define `status=OPEN`, `source=MANUAL`, `opened_at=now`, `updated_at=now`, `acknowledged_at=now` somente se `assigneeId != null`, normaliza serviços (trim, sem vazios, sem duplicatas) e métodos derivados `Long mttaSeconds()` / `Long mttrSeconds()` (null quando pendente). Métodos de transição ficam para US1 (T018)
- [X] T012 [P] Criar `MAIN/incident/domain/IncidentRepository.java` (`JpaRepository<Incident, UUID>` + `JpaSpecificationExecutor<Incident>`, com `Optional<Incident> findByIdAndOrganizationId(UUID id, UUID organizationId)`) e `MAIN/incident/domain/IncidentEventRepository.java` (`JpaRepository<IncidentEvent, UUID>`)
- [X] T013 [P] Criar exceções `MAIN/incident/IncidentNotFoundException.java` e `MAIN/incident/InvalidAssigneeException.java`, e `MAIN/incident/IncidentExceptionHandler.java` (`@RestControllerAdvice(basePackageClasses = IncidentController.class)` — criar `IncidentController` vazio com `@RestController @RequestMapping("/api/incidents")` se ainda não existir) devolvendo `ProblemDetail`: 404 para `IncidentNotFoundException`; 400 para `InvalidAssigneeException`, `MethodArgumentNotValidException` (detail `campo: mensagem`), `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` e `IllegalArgumentException`. NÃO capturar `AccessDeniedException` nem `Exception` genérica
- [X] T014 [P] Criar `MAIN/incident/dto/IncidentResponse.java` (record com todos os campos do contrato, incluindo `mttaSeconds`/`mttrSeconds`, e `static from(Incident)`)
- [X] T015 Criar `TEST/incident/IncidentTestSupport.java` (utilitário para os ITs): cria organizações e usuários direto via `JdbcTemplate` (necessário para as FKs `created_by`/`assignee_id`/`actor_id`) e gera `RequestPostProcessor` com `SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(userId).claim("tenant_id", orgId)).authorities(new SimpleGrantedAuthority("ROLE_" + role))`; e `TEST/incident/AbstractIncidentIT.java` (`@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`, limpa `incident_events`, `incidents`, `users`, `organizations` antes de cada teste e cria duas organizações A e B com usuários ADMIN, EDITOR, VIEWER, EXECUTIVE em A e um ADMIN em B)

**Checkpoint**: `.\mvnw.cmd test` compila e `ModularityTests` passa; fundação pronta

---

## Phase 3: User Story 1 - Registrar e acompanhar um incidente (Priority: P1) 🎯 MVP

**Goal**: ADMIN/EDITOR registram incidentes, alteram campos, status e responsável; datas de
reconhecimento e resolução corretas; isolamento por organização.

**Independent Test**: criar um incidente, atribuir responsável, percorrer os status até
RESOLVED e conferir datas, eventos persistidos, 403 para VIEWER/EXECUTIVE e 404 para outra
organização.

### Tests for User Story 1 ⚠️ (escrever primeiro e ver falhar)

- [X] T016 [P] [US1] Teste de unidade JUnit puro `TEST/incident/domain/IncidentTest.java` cobrindo todas as linhas da tabela "Transições e regras" do [data-model.md](data-model.md): criação (`OPEN`, `acknowledged_at` só com responsável); OPEN→INVESTIGATING define `acknowledged_at` uma única vez; mesmo status → nenhum evento; OPEN→RESOLVED define reconhecimento e resolução no mesmo instante; RESOLVED→INVESTIGATING limpa `resolved_at`, mantém reconhecimento e gera `REOPENED`; atribuição com status OPEN reconhece; remover responsável não altera reconhecimento; mudança de severidade gera `SEVERITY_CHANGED` com old/new; editar título/descrição/serviços não gera evento; `mttaSeconds`=720 para aberto 10:00 e reconhecido 10:12; `mttrSeconds`=5400 para aberto 10:00 e resolvido 11:30; nulos quando pendentes
- [X] T017 [P] [US1] Teste de unidade com Mockito `TEST/incident/IncidentServiceTest.java` (`@ExtendWith(MockitoExtension.class)`, mocks de `IncidentRepository`, `IncidentEventRepository`, `OrganizationMembers`, `CurrentUser`; `Clock.fixed`): `create` salva incidente com `organizationId` do usuário autenticado e evento `OPENED`; `create` com responsável de outra organização lança `InvalidAssigneeException`; `update` de incidente inexistente/outro tenant lança `IncidentNotFoundException`; `update` salva os eventos retornados pela entidade; `assign` valida membro e salva `ASSIGNEE_CHANGED`
- [X] T018 [P] [US1] Teste de integração `TEST/incident/IncidentLifecycleIT.java` (extends `AbstractIncidentIT`): POST 201 com `Location` e `status=OPEN`; GET `/{id}` 200; PATCH status INVESTIGATING → `acknowledgedAt` preenchido; PATCH RESOLVED → `resolvedAt` e `mttrSeconds`; PUT `/assignee` com usuário de A → 200 e com usuário de B → 400; PATCH com corpo vazio → 400; POST com `title` vazio ou `severity` `SEV9` → 400 com `application/problem+json`; POST/PATCH/PUT por VIEWER e EXECUTIVE → 403; GET/PATCH do incidente de A com token de B → 404; sem token → 401

### Implementation for User Story 1

- [X] T019 [US1] Implementar em `MAIN/incident/domain/Incident.java` os métodos de transição que retornam `List<IncidentEvent>` gerados: `changeStatus(IncidentStatus, UUID actor, Instant now)`, `changeSeverity(Severity, UUID actor, Instant now)`, `assign(UUID assigneeIdOrNull, UUID actor, Instant now)`, `edit(String title, String description, List<String> affectedServices, Instant now)` (null = sem alteração), seguindo exatamente a tabela do [data-model.md](data-model.md); toda alteração efetiva atualiza `updated_at`
- [X] T020 [P] [US1] Criar DTOs em `MAIN/incident/dto/`: `CreateIncidentRequest` (`@NotBlank @Size(max=200) title`, `@Size(max=10000) description`, `@NotNull Severity severity`, `@Size(max=20) List<@NotBlank @Size(max=100) String> affectedServices`, `UUID assigneeId`), `UpdateIncidentRequest` (mesmos limites, todos opcionais, mais `IncidentStatus status`; método `isEmpty()`), `AssignIncidentRequest` (`UUID assigneeId`, nulo remove)
- [X] T021 [US1] Implementar `MAIN/incident/IncidentService.java` (`@Service`, injeta repositórios, `OrganizationMembers`, `CurrentUser`, `Clock`): `create`, `get(UUID id)`, `update(UUID id, UpdateIncidentRequest)` (lança `IllegalArgumentException` se vazio), `assign(UUID id, AssignIncidentRequest)`; todos `@Transactional`, sempre buscando via `findByIdAndOrganizationId` com o tenant de `CurrentUser`, validando responsável com `OrganizationMembers.isMember` e persistindo os eventos devolvidos pela entidade
- [X] T022 [US1] Implementar em `MAIN/incident/IncidentController.java`: `POST /api/incidents` (201 + `Location`), `GET /api/incidents/{id}`, `PATCH /api/incidents/{id}`, `PUT /api/incidents/{id}/assignee`, com `@Valid` e `@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")` nas escritas e `@PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER','EXECUTIVE')")` na leitura, conforme [contracts/incidents-api.md](contracts/incidents-api.md)
- [X] T023 [US1] Rodar `.\mvnw.cmd test` em `backend/` até T016–T018 passarem

**Checkpoint**: US1 funcional e testada isoladamente (MVP)

---

## Phase 4: User Story 2 - Listar e filtrar incidentes (Priority: P1)

**Goal**: listar incidentes da própria organização com filtros combináveis e paginação.

**Independent Test**: com incidentes de status, severidades e datas diferentes, cada filtro
retorna apenas os correspondentes, ordenados por `openedAt` desc, e B não vê incidentes de A.

### Tests for User Story 2 ⚠️

- [X] T024 [P] [US2] Teste de integração `TEST/incident/IncidentListIT.java`: inserir incidentes via `JdbcTemplate` com `opened_at` controlados; verificar filtro `status=OPEN&status=INVESTIGATING`, `severity=SEV1`, `openedFrom` (inclusivo) / `openedTo` (exclusivo), combinação dos três, ordenação `openedAt` desc, paginação (`page`, `size`, `totalElements`, `totalPages`), `size=500` limitado a 100, lista vazia retorna `content: []` com 200, `status=FOO` → 400, token de B não vê incidentes de A, VIEWER e EXECUTIVE conseguem listar

### Implementation for User Story 2

- [X] T025 [P] [US2] Criar `MAIN/incident/domain/IncidentSpecifications.java` com `Specification<Incident>` para organização (sempre aplicada), `statusIn`, `severityIn`, `openedFrom` (>=) e `openedTo` (<), ignorando filtros nulos/vazios
- [X] T026 [P] [US2] Criar `MAIN/incident/dto/PageResponse.java` (record genérico `content, page, size, totalElements, totalPages` com `static from(Page<T>)`)
- [X] T027 [US2] Adicionar `list(Set<IncidentStatus>, Set<Severity>, Instant openedFrom, Instant openedTo, int page, int size)` em `MAIN/incident/IncidentService.java` (tamanho padrão 20, máximo 100, ordenação fixa `openedAt DESC`, `@Transactional(readOnly = true)`)
- [X] T028 [US2] Adicionar `GET /api/incidents` em `MAIN/incident/IncidentController.java` com `@RequestParam(required=false)` para `status`, `severity` (listas), `openedFrom`, `openedTo` (`Instant`, ISO-8601), `page`, `size`, papéis de leitura
- [X] T029 [US2] Rodar `.\mvnw.cmd test` até T024 passar sem quebrar US1

**Checkpoint**: US1 e US2 funcionam de forma independente

---

## Phase 5: User Story 3 - Linha do tempo e comentários (Priority: P2)

**Goal**: consultar a linha do tempo cronológica gerada automaticamente e adicionar comentários.

**Independent Test**: criar incidente, mudar status/severidade/responsável, comentar e conferir
a linha do tempo em ordem com autor, horário e valores antigo/novo.

### Tests for User Story 3 ⚠️

- [X] T030 [P] [US3] Acrescentar em `TEST/incident/IncidentServiceTest.java` testes de `comment` (salva evento `COMMENT` com autor e `Clock`; incidente de outro tenant → `IncidentNotFoundException`) e de `timeline` (consulta ordenada do repositório, 404 para outro tenant)
- [X] T031 [P] [US3] Teste de integração `TEST/incident/IncidentTimelineIT.java`: após POST, PATCH status, PATCH severidade, PUT responsável e POST comentário, `GET /{id}/timeline` retorna `OPENED`, `STATUS_CHANGED`, `SEVERITY_CHANGED`, `ASSIGNEE_CHANGED`, `COMMENT` em ordem, com `actorId`, `oldValue`/`newValue` e `body`; PATCH com o mesmo status não adiciona evento; resolver e reabrir gera `REOPENED`; comentário vazio ou com mais de 5.000 caracteres → 400; comentário por VIEWER/EXECUTIVE → 403; timeline e comentário em incidente de A com token de B → 404; não existem rotas para editar/apagar eventos (PUT/DELETE em `/timeline/{id}` → 404 ou 405)

### Implementation for User Story 3

- [X] T032 [P] [US3] Criar `MAIN/incident/dto/CommentRequest.java` (`@NotBlank @Size(max=5000) String body`) e `MAIN/incident/dto/IncidentEventResponse.java` (record `id, type, actorId, occurredAt, oldValue, newValue, body` com `static from(IncidentEvent)`)
- [X] T033 [US3] Adicionar em `MAIN/incident/domain/IncidentEventRepository.java` o método `List<IncidentEvent> findByIncidentIdAndOrganizationIdOrderByOccurredAtAscSeqAsc(UUID incidentId, UUID organizationId)`
- [X] T034 [US3] Adicionar `timeline(UUID id)` (`readOnly`) e `comment(UUID id, CommentRequest)` em `MAIN/incident/IncidentService.java`; ambos validam que o incidente existe no tenant; `comment` também atualiza `updated_at` do incidente
- [X] T035 [US3] Adicionar `GET /api/incidents/{id}/timeline` (papéis de leitura) e `POST /api/incidents/{id}/comments` (201, ADMIN/EDITOR) em `MAIN/incident/IncidentController.java`
- [X] T036 [US3] Rodar `.\mvnw.cmd test` até T030–T031 passarem sem quebrar US1/US2

**Checkpoint**: US1–US3 funcionando

---

## Phase 6: User Story 4 - Métricas MTTA e MTTR (Priority: P2)

**Goal**: MTTA/MTTR agregados por período, opcionalmente por severidade.

**Independent Test**: com incidentes de tempos conhecidos, as médias retornadas batem com o
cálculo manual e incidentes pendentes ficam fora da média.

### Tests for User Story 4 ⚠️

- [X] T037 [P] [US4] Acrescentar em `TEST/incident/IncidentServiceTest.java` testes de `metrics`: padrão `openedFrom = now − 30 dias` e `openedTo = now` com `Clock.fixed`; `openedTo <= openedFrom` → `IllegalArgumentException`; `groupBy` diferente de `severity` → `IllegalArgumentException`; montagem da resposta a partir das linhas do repositório
- [X] T038 [P] [US4] Teste de integração `TEST/incident/IncidentMetricsIT.java`: inserir via `JdbcTemplate` incidentes em A com `opened_at`/`acknowledged_at`/`resolved_at` conhecidos (incluindo um não reconhecido e um não resolvido) e um incidente em B; verificar `overall.count`, `mttaSeconds` e `mttrSeconds` médios (arredondados) ignorando pendentes; `groupBy=severity` retorna `bySeverity` correto; sem `groupBy`, `bySeverity` ausente; nenhum dado de B entra nas métricas de A; período sem incidentes → `count=0` e médias `null`; `openedTo` anterior a `openedFrom` → 400; VIEWER e EXECUTIVE → 200

### Implementation for User Story 4

- [X] T039 [P] [US4] Criar `MAIN/incident/dto/IncidentMetricsResponse.java` (records `IncidentMetricsResponse(openedFrom, openedTo, MetricsBucket overall, List<SeverityMetricsBucket> bySeverity)` com `@JsonInclude(NON_NULL)` em `bySeverity`, `MetricsBucket(long count, Long mttaSeconds, Long mttrSeconds)`, `SeverityMetricsBucket(Severity severity, long count, Long mttaSeconds, Long mttrSeconds)`)
- [X] T040 [US4] Adicionar em `MAIN/incident/domain/IncidentRepository.java` duas consultas nativas com projeções de interface: total e agrupado por `severity`, usando `count(*)`, `round(avg(extract(epoch from acknowledged_at - opened_at)) FILTER (WHERE acknowledged_at IS NOT NULL))` e o equivalente para `resolved_at`, filtrando `organization_id = :org AND opened_at >= :from AND opened_at < :to`
- [X] T041 [US4] Adicionar `metrics(Instant openedFrom, Instant openedTo, String groupBy)` (`readOnly`) em `MAIN/incident/IncidentService.java` com os padrões e validações de T037
- [X] T042 [US4] Adicionar `GET /api/incidents/metrics` (papéis de leitura) em `MAIN/incident/IncidentController.java`, garantindo que não conflite com `GET /api/incidents/{id}`
- [X] T043 [US4] Rodar `.\mvnw.cmd test` até T037–T038 passarem sem quebrar US1–US3

**Checkpoint**: todas as histórias funcionando

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T044 Teste de desempenho `TEST/incident/IncidentPerformanceIT.java` (SC-005): inserir 10.000 incidentes na organização A via `INSERT ... SELECT FROM generate_series(1, 10000)` com status, severidade e `opened_at` variados; aquecer uma chamada e então verificar que `GET /api/incidents?status=OPEN&severity=SEV1&openedFrom=...` e `GET /api/incidents/metrics?groupBy=severity` respondem em menos de 2 s cada (medir com `System.nanoTime()`)
- [X] T045 Rodar a suíte completa `.\mvnw.cmd test` em `backend/` (unidade, integração, desempenho e `ModularityTests`) e corrigir falhas
- [X] T046 Verificar que a aplicação sobe com o banco de dev (`docker compose up -d` + `.\mvnw.cmd spring-boot:run`) e que o Flyway aplica `V3` sem erro com `ddl-auto=validate`
- [ ] T047 Executar o roteiro manual de [quickstart.md](quickstart.md) (passos 1–10) — **validação feita pelo desenvolvedor**
- [X] T048 [P] Atualizar `Status` de [spec.md](spec.md) para `Implemented` e marcar no [plan.md](plan.md) qualquer desvio ocorrido durante a implementação

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sem dependências
- **Foundational (Phase 2)**: depende do Setup — BLOQUEIA todas as histórias
- **US1 (Phase 3)**: depende da Fundação
- **US2 (Phase 4)**: depende da Fundação; testes inserem dados via SQL, então não depende de US1 (mas compartilha `IncidentService`/`IncidentController`, então em time único fazer após US1)
- **US3 (Phase 5)**: depende de US1 (os eventos são gerados pelas operações de US1)
- **US4 (Phase 6)**: depende da Fundação; testes inserem dados via SQL
- **Polish (Phase 7)**: depende das histórias desejadas

### Within Each User Story

- Testes escritos e falhando antes da implementação
- Domínio → DTOs → serviço → controller → rodar testes

### Parallel Opportunities

- Setup: T002 e T003
- Fundação: T005, T006, T007, T009 em paralelo; depois T012, T013, T014 em paralelo
- Em cada história, os testes marcados [P] podem ser escritos em paralelo
- US2 e US4 podem ser feitas em paralelo por pessoas diferentes após US1 (conflito apenas em `IncidentService.java`/`IncidentController.java` — combinar merge)

## Parallel Example: User Story 1

```text
Task: "T016 Teste de unidade IncidentTest em TEST/incident/domain/IncidentTest.java"
Task: "T017 Teste Mockito IncidentServiceTest em TEST/incident/IncidentServiceTest.java"
Task: "T018 Teste de integração IncidentLifecycleIT em TEST/incident/IncidentLifecycleIT.java"
```

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 + Phase 2
2. Phase 3 (US1) → **PARAR e validar** (testes + passos 1–5 do quickstart)

### Incremental Delivery

1. Fundação → US1 (MVP) → US2 (lista) → US3 (linha do tempo) → US4 (métricas)
2. Cada história com seus testes verdes antes da próxima; commit por história citando `MON-10`

## Notes

- [P] = arquivos diferentes, sem dependência pendente
- Commits no formato Conventional Commits, ex.: `feat(incident): MON-10 US1 registrar incidentes`
- Não implementar nada de MON-09, NEG-03 ou AI-01 além do campo `source` e dos tipos de evento extensíveis
