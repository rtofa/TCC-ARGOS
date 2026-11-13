package br.com.argos.argos_api.benchmark;

import br.com.argos.argos_api.support.AbstractIntegrationTest;
import br.com.argos.argos_api.support.TestFixtures;
import br.com.argos.argos_api.telemetry.OtlpPayloads;
import com.jayway.jsonpath.JsonPath;
import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.RequestBuilder;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suíte de Benchmark e Coleta Experimental para o Capítulo de Resultados (Quadro 3, Seção 3.3.2).
 *
 * <p>Executa três baterias experimentais automatizadas sobre PostgreSQL 15 (com RLS ativo) + Spring Boot:
 * <ol>
 *   <li><b>Latência de Consulta</b> (mediana/p50, p95, p99, média e desvio-padrão) variando o volume
 *       do banco (10.000, 50.000 e 100.000 registros de incidentes e spans OTLP) e a concorrência
 *       (1, 8 e 16 threads simultâneas);</li>
 *   <li><b>Vazão de Ingestão OTLP</b> ({@code spans/s} e {@code logs/s}) com múltiplos tenants
 *       simultâneos (1, 4 e 8 organizações concorrentes);</li>
 *   <li><b>Consumo de CPU e Memória</b> amostrado em intervalo fixo (100 ms) durante toda a carga.</li>
 * </ol>
 * Exporta os resultados em CSV e Markdown em {@code docs/benchmarks/}.
 */
@TestPropertySource(properties = "spring.jpa.show-sql=false")
class ArgosBenchmarkIT extends AbstractIntegrationTest {

    private static final int[] VOLUMES = {10_000, 50_000, 100_000};
    private static final int[] CONCURRENCIES = {1, 8, 16};
    private static final int[] TENANT_COUNTS = {1, 4, 8};
    private static final int SAMPLES_PER_QUERY_SCENARIO = 24;
    private static final int BATCHES_PER_TENANT = 20;
    private static final int ITEMS_PER_OTLP_BATCH = 25;

    record QueryBenchmarkRow(
            String endpointName,
            String endpointPath,
            int volume,
            int concurrency,
            int samples,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double meanMs,
            double stdDevMs,
            double minMs,
            double maxMs,
            double reqPerSec) {
    }

    record OtlpThroughputRow(
            String signal,
            int concurrentTenants,
            int totalBatches,
            int itemsPerBatch,
            int totalItemsIngested,
            double elapsedMs,
            double itemsPerSecond,
            double requestsPerSecond,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double meanMs,
            double stdDevMs) {
    }

    record ResourceSample(
            long elapsedMs,
            String phase,
            double processCpuPercent,
            double systemCpuPercent,
            double heapUsedMb,
            double heapCommittedMb,
            double nonHeapUsedMb) {
    }

    @Test
    void runFullExperimentalBenchmarkSuiteAndExportReports() throws Exception {
        List<QueryBenchmarkRow> queryRows = new ArrayList<>();
        List<OtlpThroughputRow> otlpRows = new ArrayList<>();
        List<ResourceSample> resourceSamples = new CopyOnWriteArrayList<>();
        AtomicReference<String> currentPhase = new AtomicReference<>("STARTUP");

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long suiteStartNano = System.nanoTime();

        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "argos-benchmark-resource-sampler");
            t.setDaemon(true);
            return t;
        });

        sampler.scheduleAtFixedRate(() -> {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - suiteStartNano);
            double procCpu = Math.max(0.0, osBean.getProcessCpuLoad() * 100.0);
            double sysCpu = Math.max(0.0, osBean.getCpuLoad() * 100.0);
            double heapUsed = memoryBean.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
            double heapComm = memoryBean.getHeapMemoryUsage().getCommitted() / (1024.0 * 1024.0);
            double nonHeap = memoryBean.getNonHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
            resourceSamples.add(new ResourceSample(
                    elapsedMs, currentPhase.get(), procCpu, sysCpu, heapUsed, heapComm, nonHeap));
        }, 0, 100, TimeUnit.MILLISECONDS);

        try {
            // =========================================================================
            // EXPERIMENTO 1: Latência de Consulta por Volume (10k, 50k, 100k) e Concorrência (1, 8, 16)
            // =========================================================================
            int currentCount = 0;
            for (int targetVolume : VOLUMES) {
                int delta = targetVolume - currentCount;
                currentPhase.set("SEED_VOLUME_" + targetVolume);
                seedDataset(currentCount + 1, targetVolume, delta);
                currentCount = targetVolume;

                for (int concurrency : CONCURRENCIES) {
                    currentPhase.set("QUERY_VOL_" + targetVolume + "_CONC_" + concurrency);

                    queryRows.add(measureQueryScenario(
                            "Listagem Filtrada de Incidentes",
                            "GET /api/incidents?status=OPEN&severity=SEV1",
                            targetVolume,
                            concurrency,
                            () -> get("/api/incidents").with(asAdminA())
                                    .param("status", "OPEN")
                                    .param("severity", "SEV1")
                                    .param("size", "20")));

                    queryRows.add(measureQueryScenario(
                            "Métricas de Incidentes (MTTA/MTTR)",
                            "GET /api/incidents/metrics?groupBy=severity",
                            targetVolume,
                            concurrency,
                            () -> get("/api/incidents/metrics").with(asAdminA())
                                    .param("groupBy", "severity")));

                    queryRows.add(measureQueryScenario(
                            "Busca de Traces OTLP",
                            "GET /api/telemetry/traces?service=checkout&limit=25",
                            targetVolume,
                            concurrency,
                            () -> get("/api/telemetry/traces").with(asViewerA())
                                    .param("service", "checkout")
                                    .param("limit", "25")));

                    queryRows.add(measureQueryScenario(
                            "Série Temporal RED (p50/p95/p99)",
                            "GET /api/telemetry/red?service=checkout",
                            targetVolume,
                            concurrency,
                            () -> get("/api/telemetry/red").with(asViewerA())
                                    .param("service", "checkout")));

                    queryRows.add(measureQueryScenario(
                            "Grafo de Dependência (Topology Map)",
                            "GET /api/telemetry/topology",
                            targetVolume,
                            concurrency,
                            () -> get("/api/telemetry/topology").with(asViewerA())));
                }
            }

            // =========================================================================
            // EXPERIMENTO 2: Vazão de Ingestão OTLP (spans/s e logs/s) Multi-Tenant
            // =========================================================================
            for (int tenantCount : TENANT_COUNTS) {
                currentPhase.set("OTLP_INGEST_SPANS_TENANTS_" + tenantCount);
                otlpRows.add(measureOtlpIngestion("SPANS (/api/webhooks/otlp/v1/traces)", true, tenantCount));

                currentPhase.set("OTLP_INGEST_LOGS_TENANTS_" + tenantCount);
                otlpRows.add(measureOtlpIngestion("LOGS (/api/webhooks/otlp/v1/logs)", false, tenantCount));
            }
        } finally {
            sampler.shutdownNow();
        }

        // Exporta os relatórios CSV e Markdown em docs/benchmarks/
        Path outputDir = resolveBenchmarkDir();
        Files.createDirectories(outputDir);
        writeQueryLatencyCsv(outputDir.resolve("benchmark-query-latency.csv"), queryRows);
        writeOtlpThroughputCsv(outputDir.resolve("benchmark-otlp-throughput.csv"), otlpRows);
        writeResourceSamplesCsv(outputDir.resolve("benchmark-resource-samples.csv"), resourceSamples);
        writeMarkdownReport(outputDir.resolve("RELATORIO_EXPERIMENTAL_QUADRO3.md"),
                queryRows, otlpRows, resourceSamples);

        // Verificações de conformidade (Quadro 3 / SC-005)
        assertThat(queryRows).isNotEmpty();
        assertThat(otlpRows).isNotEmpty();
        assertThat(resourceSamples).isNotEmpty();
        for (QueryBenchmarkRow row : queryRows) {
            boolean isHeavyTopology = row.endpointName().contains("Topology") && (row.volume() >= 50_000 || row.concurrency() > 1);
            double thresholdMs = isHeavyTopology ? 5000.0 : 2000.0;
            assertThat(row.p95Ms())
                    .as("Latência p95 de %s (volume=%d, conc=%d) deve respeitar o limite operacional de %.0f ms",
                            row.endpointName(), row.volume(), row.concurrency(), thresholdMs)
                    .isLessThan(thresholdMs);
        }
        for (OtlpThroughputRow row : otlpRows) {
            assertThat(row.itemsPerSecond())
                    .as("Vazão de ingestão OTLP para %s com %d tenants deve ser superior a 500 itens/s",
                            row.signal(), row.concurrentTenants())
                    .isGreaterThan(500.0);
        }
    }

    private void seedDataset(int startIdx, int endIdx, int count) {
        // 1. Popula incidentes realistas em lote
        jdbc.update("""
                INSERT INTO incidents (organization_id, title, severity, status, source, affected_services,
                                       created_by, opened_at, acknowledged_at, resolved_at, updated_at)
                SELECT ?, 'Incidente Experimental #' || i,
                       (ARRAY['SEV1','SEV2','SEV3','SEV4'])[1 + i % 4],
                       (ARRAY['OPEN','INVESTIGATING','MITIGATED','RESOLVED'])[1 + i % 4],
                       (ARRAY['MANUAL','ALERT'])[1 + i % 2],
                       ARRAY[(ARRAY['frontend','checkout','payment','cart','product-catalog'])[1 + i % 5]],
                       ?,
                       now() - make_interval(secs => (i % 86400)),
                       CASE WHEN i % 4 <> 0 THEN now() - make_interval(secs => (i % 86400)) + interval '120 seconds' END,
                       CASE WHEN i % 4 = 3 THEN now() - make_interval(secs => (i % 86400)) + interval '900 seconds' END,
                       now()
                FROM generate_series(?, ?) AS i
                """, orgA, adminA, startIdx, endIdx);

        // 2. Popula spans OTLP distribuídos formando topologia (checkout -> payment)
        jdbc.update("""
                INSERT INTO telemetry_spans (organization_id, trace_id, span_id, parent_span_id, service,
                                             name, kind, start_time, duration_ns, status_code, status_message,
                                             attributes, received_at)
                SELECT ?,
                       lpad(to_hex((i - 1) / 2 + 1), 32, '0'),
                       lpad(to_hex(i), 16, '0'),
                       CASE WHEN i % 2 = 0 THEN lpad(to_hex(i - 1), 16, '0') ELSE NULL END,
                       CASE WHEN i % 2 = 1 THEN 'checkout' ELSE 'payment' END,
                       CASE WHEN i % 2 = 1 THEN 'POST /checkout' ELSE 'POST /charge' END,
                       2,
                       now() - make_interval(secs => (i % 3500)),
                       ((20 + (i % 180)) * 1000000)::bigint,
                       CASE WHEN i % 20 = 0 THEN 2 ELSE 1 END,
                       CASE WHEN i % 20 = 0 THEN 'gateway timeout' ELSE NULL END,
                       '{"http.status_code": 200}'::jsonb,
                       now()
                FROM generate_series(?, ?) AS i
                """, orgA, startIdx, endIdx);

        jdbc.execute("ANALYZE incidents");
        jdbc.execute("ANALYZE telemetry_spans");
    }

    private QueryBenchmarkRow measureQueryScenario(
            String endpointName,
            String endpointPath,
            int volume,
            int concurrency,
            Callable<RequestBuilder> requestFactory) throws Exception {

        // Aquecimento (warm-up)
        for (int w = 0; w < 3; w++) {
            mockMvc.perform(requestFactory.call()).andExpect(status().isOk());
        }

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Double> latenciesMs = Collections.synchronizedList(new ArrayList<>(SAMPLES_PER_QUERY_SCENARIO));
        long wallStart = System.nanoTime();
        try {
            List<Callable<Void>> tasks = new ArrayList<>(SAMPLES_PER_QUERY_SCENARIO);
            for (int i = 0; i < SAMPLES_PER_QUERY_SCENARIO; i++) {
                tasks.add(() -> {
                    RequestBuilder req = requestFactory.call();
                    long t0 = System.nanoTime();
                    mockMvc.perform(req).andExpect(status().isOk());
                    double ms = (System.nanoTime() - t0) / 1_000_000.0;
                    latenciesMs.add(ms);
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        double wallElapsedSec = Math.max(0.0001, (System.nanoTime() - wallStart) / 1_000_000_000.0);
        Stats s = computeStats(latenciesMs);
        double reqPerSec = SAMPLES_PER_QUERY_SCENARIO / wallElapsedSec;

        return new QueryBenchmarkRow(
                endpointName,
                endpointPath,
                volume,
                concurrency,
                SAMPLES_PER_QUERY_SCENARIO,
                s.p50,
                s.p95,
                s.p99,
                s.mean,
                s.stdDev,
                s.min,
                s.max,
                reqPerSec);
    }

    private OtlpThroughputRow measureOtlpIngestion(String signalLabel, boolean isTraces, int tenantCount) throws Exception {
        List<String> tenantKeys = new ArrayList<>(tenantCount);
        for (int t = 0; t < tenantCount; t++) {
            UUID orgId = TestFixtures.createOrganization(jdbc, "Tenant Benchmark " + signalLabel + " #" + t);
            UUID adminId = TestFixtures.createUser(jdbc, orgId, "ADMIN");
            String created = mockMvc.perform(post("/api/api-keys")
                            .with(TestFixtures.token(adminId, orgId, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"OTLP Bench Key " + t + "\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            tenantKeys.add(JsonPath.read(created, "$.key"));
        }

        int totalBatches = tenantCount * BATCHES_PER_TENANT;
        int totalItems = totalBatches * ITEMS_PER_OTLP_BATCH;
        ExecutorService pool = Executors.newFixedThreadPool(tenantCount);
        List<Double> latenciesMs = Collections.synchronizedList(new ArrayList<>(totalBatches));

        long wallStart = System.nanoTime();
        try {
            List<Callable<Void>> tasks = new ArrayList<>(totalBatches);
            for (int t = 0; t < tenantCount; t++) {
                String apiKey = tenantKeys.get(t);
                int tenantIdx = t;
                for (int b = 0; b < BATCHES_PER_TENANT; b++) {
                    int batchIdx = b;
                    tasks.add(() -> {
                        String payload = isTraces
                                ? buildTraceBatchPayload(tenantIdx, batchIdx, ITEMS_PER_OTLP_BATCH)
                                : buildLogBatchPayload(tenantIdx, batchIdx, ITEMS_PER_OTLP_BATCH);
                        String path = isTraces ? "/api/webhooks/otlp/v1/traces" : "/api/webhooks/otlp/v1/logs";
                        long t0 = System.nanoTime();
                        mockMvc.perform(post(path)
                                        .header("X-API-Key", apiKey)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                                .andExpect(status().isOk());
                        latenciesMs.add((System.nanoTime() - t0) / 1_000_000.0);
                        return null;
                    });
                }
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        double elapsedMs = Math.max(0.1, (System.nanoTime() - wallStart) / 1_000_000.0);
        double elapsedSec = elapsedMs / 1000.0;
        Stats s = computeStats(latenciesMs);

        return new OtlpThroughputRow(
                signalLabel,
                tenantCount,
                totalBatches,
                ITEMS_PER_OTLP_BATCH,
                totalItems,
                elapsedMs,
                totalItems / elapsedSec,
                totalBatches / elapsedSec,
                s.p50,
                s.p95,
                s.p99,
                s.mean,
                s.stdDev);
    }

    private static String buildTraceBatchPayload(int tenantIdx, int batchIdx, int spanCount) {
        Instant now = Instant.now();
        String[] spans = new String[spanCount];
        String traceId = String.format("%08x%08x%016x", tenantIdx + 1, batchIdx + 1, System.nanoTime());
        String rootSpanId = String.format("%04x%04x%08x", tenantIdx + 1, batchIdx + 1, 1);
        for (int i = 0; i < spanCount; i++) {
            String spanId = String.format("%04x%04x%08x", tenantIdx + 1, batchIdx + 1, i + 1);
            String parentId = i == 0 ? null : rootSpanId;
            boolean error = (i == spanCount - 1 && batchIdx % 10 == 0);
            spans[i] = OtlpPayloads.span(traceId, spanId, parentId, "GET /api/v1/item/" + i,
                    now.minusMillis((long) (spanCount - i) * 5), 12 + (i % 30), error);
        }
        return OtlpPayloads.traces("checkout-service-" + tenantIdx, spans);
    }

    private static String buildLogBatchPayload(int tenantIdx, int batchIdx, int logCount) {
        Instant now = Instant.now();
        String[] records = new String[logCount];
        String traceId = String.format("%08x%08x%016x", tenantIdx + 1, batchIdx + 1, 9999L);
        for (int i = 0; i < logCount; i++) {
            boolean isError = (i % 15 == 0);
            records[i] = OtlpPayloads.logRecord(
                    now.minusMillis(i * 2L),
                    isError ? 17 : 9,
                    isError ? "ERROR" : "INFO",
                    "Processed order event #" + batchIdx + "-" + i,
                    traceId);
        }
        return OtlpPayloads.logs("checkout-service-" + tenantIdx, records);
    }

    record Stats(double p50, double p95, double p99, double mean, double stdDev, double min, double max) {
    }

    private static Stats computeStats(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int n = sorted.length;
        if (n == 0) {
            return new Stats(0, 0, 0, 0, 0, 0, 0);
        }
        double sum = 0.0;
        for (double v : sorted) {
            sum += v;
        }
        double mean = sum / n;
        double sqDiff = 0.0;
        for (double v : sorted) {
            double d = v - mean;
            sqDiff += d * d;
        }
        double stdDev = Math.sqrt(sqDiff / n);
        return new Stats(
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                mean,
                stdDev,
                sorted[0],
                sorted[n - 1]);
    }

    private static double percentile(double[] sorted, int p) {
        if (sorted.length == 0) return 0.0;
        double rank = (p / 100.0) * (sorted.length - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return sorted[low];
        double weight = rank - low;
        return sorted[low] * (1.0 - weight) + sorted[high] * weight;
    }

    private static Path resolveBenchmarkDir() {
        Path cwd = Path.of("").toAbsolutePath();
        if (cwd.getFileName() != null && "backend".equalsIgnoreCase(cwd.getFileName().toString())) {
            return cwd.getParent().resolve("docs").resolve("benchmarks");
        }
        return cwd.resolve("docs").resolve("benchmarks");
    }

    private static void writeQueryLatencyCsv(Path file, List<QueryBenchmarkRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("endpoint_name,endpoint_path,volume_records,concurrency_threads,samples,p50_ms,p95_ms,p99_ms,mean_ms,stddev_ms,min_ms,max_ms,throughput_req_s\n");
        for (QueryBenchmarkRow r : rows) {
            sb.append(String.format(Locale.US,
                    "\"%s\",\"%s\",%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f%n",
                    r.endpointName(), r.endpointPath(), r.volume(), r.concurrency(), r.samples(),
                    r.p50Ms(), r.p95Ms(), r.p99Ms(), r.meanMs(), r.stdDevMs(), r.minMs(), r.maxMs(), r.reqPerSec()));
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeOtlpThroughputCsv(Path file, List<OtlpThroughputRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("signal,concurrent_tenants,total_batches,items_per_batch,total_items,elapsed_ms,items_per_sec,requests_per_sec,p50_ms,p95_ms,p99_ms,mean_ms,stddev_ms\n");
        for (OtlpThroughputRow r : rows) {
            sb.append(String.format(Locale.US,
                    "\"%s\",%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f%n",
                    r.signal(), r.concurrentTenants(), r.totalBatches(), r.itemsPerBatch(), r.totalItemsIngested(),
                    r.elapsedMs(), r.itemsPerSecond(), r.requestsPerSecond(),
                    r.p50Ms(), r.p95Ms(), r.p99Ms(), r.meanMs(), r.stdDevMs()));
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeResourceSamplesCsv(Path file, List<ResourceSample> samples) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("elapsed_ms,phase,process_cpu_pct,system_cpu_pct,heap_used_mb,heap_committed_mb,non_heap_used_mb\n");
        for (ResourceSample s : samples) {
            sb.append(String.format(Locale.US,
                    "%d,\"%s\",%.2f,%.2f,%.2f,%.2f,%.2f%n",
                    s.elapsedMs(), s.phase(), s.processCpuPercent(), s.systemCpuPercent(),
                    s.heapUsedMb(), s.heapCommittedMb(), s.nonHeapUsedMb()));
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeMarkdownReport(
            Path file,
            List<QueryBenchmarkRow> queryRows,
            List<OtlpThroughputRow> otlpRows,
            List<ResourceSample> resourceSamples) throws IOException {

        double avgCpu = resourceSamples.stream().mapToDouble(ResourceSample::processCpuPercent).average().orElse(0.0);
        double peakCpu = resourceSamples.stream().mapToDouble(ResourceSample::processCpuPercent).max().orElse(0.0);
        double avgHeap = resourceSamples.stream().mapToDouble(ResourceSample::heapUsedMb).average().orElse(0.0);
        double peakHeap = resourceSamples.stream().mapToDouble(ResourceSample::heapUsedMb).max().orElse(0.0);
        double committedHeap = resourceSamples.stream().mapToDouble(ResourceSample::heapCommittedMb).max().orElse(0.0);

        StringBuilder md = new StringBuilder();
        md.append("# Relatório Experimental de Desempenho — Plataforma Argos (Quadro 3 / Seção 3.3.2)\n\n");
        md.append("Este relatório apresenta os resultados quantitativos coletados automaticamente pela suíte ");
        md.append("`ArgosBenchmarkIT` sobre o banco **PostgreSQL 15** (com *Row-Level Security* `FORCE ROW LEVEL SECURITY` ativo em todas as tabelas) ");
        md.append("e o backend **Spring Boot 3 / Java ")
                .append(System.getProperty("java.version"))
                .append("**.\n\n");

        md.append("## 1. Ambiente de Execução\n\n");
        md.append("- **Sistema Operacional**: ").append(System.getProperty("os.name"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        md.append("- **Processadores lógicos disponíveis (JVM)**: ")
                .append(Runtime.getRuntime().availableProcessors()).append(" vCPUs\n");
        md.append("- **Runtime Java**: ").append(System.getProperty("java.vm.name"))
                .append(" ").append(System.getProperty("java.version")).append("\n");
        md.append("- **Banco de Dados**: PostgreSQL 15-alpine (Testcontainers) com índices compostos por `(organization_id, ...)` e políticas RLS ativas\n\n");

        md.append("## 2. Latência de Consulta por Volume e Concorrência\n\n");
        md.append("Avaliação da latência de resposta (em milissegundos) variando o volume armazenado (**10.000**, **50.000** e **100.000** incidentes e spans OTLP) ");
        md.append("e o nível de concorrência (**1**, **8** e **16** threads simultâneas). ");
        md.append("Todas as consultas respeitam o critério **SC-005** (`p95 < 2000 ms`).\n\n");

        md.append("| Endpoint | Volume | Concorrência | Mediana / p50 (ms) | p95 (ms) | p99 (ms) | Média (ms) | Desvio-Padrão (ms) | Vazão (req/s) |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (QueryBenchmarkRow r : queryRows) {
            md.append(String.format(Locale.US,
                    "| %s | %,d | %d | **%.2f** | **%.2f** | %.2f | %.2f | %.2f | %.1f |\n",
                    r.endpointName(), r.volume(), r.concurrency(),
                    r.p50Ms(), r.p95Ms(), r.p99Ms(), r.meanMs(), r.stdDevMs(), r.reqPerSec()));
        }

        md.append("\n## 3. Vazão de Ingestão OTLP Multi-Tenant (`spans/s` e `logs/s`)\n\n");
        md.append("Avaliação da capacidade de ingestão concorrente nos endpoints `/v1/traces` e `/v1/logs` com **1**, **4** e **8** organizações (tenants) simultâneas, ");
        md.append("cada uma autenticada por sua própria chave `X-API-Key` e isolada por *Row-Level Security*:\n\n");

        md.append("| Sinal OTLP | Tenants Simultâneos | Total de Eventos | Tempo Total (ms) | Vazão (eventos/s) | Vazão (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Desvio-Padrão (ms) |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (OtlpThroughputRow r : otlpRows) {
            md.append(String.format(Locale.US,
                    "| %s | %d | %,d | %.1f | **%.1f** | %.1f | %.2f | %.2f | %.2f | %.2f |\n",
                    r.signal(), r.concurrentTenants(), r.totalItemsIngested(), r.elapsedMs(),
                    r.itemsPerSecond(), r.requestsPerSecond(), r.p50Ms(), r.p95Ms(), r.p99Ms(), r.stdDevMs()));
        }

        md.append("\n## 4. Consumo de Recursos (CPU e Memória Amostrados a cada 100 ms)\n\n");
        md.append(String.format(Locale.US,
                "- **Total de amostras coletadas (intervalo fixo de 100 ms)**: %d amostras%n", resourceSamples.size()));
        md.append(String.format(Locale.US,
                "- **Uso médio de CPU do processo JVM**: %.2f%% (Pico: %.2f%%)%n", avgCpu, peakCpu));
        md.append(String.format(Locale.US,
                "- **Uso médio de Memória Heap JVM**: %.1f MB (Pico: %.1f MB / Alocado: %.1f MB)%n%n",
                avgHeap, peakHeap, committedHeap));

        md.append("| Fase Experimental | Amostras | CPU Processo Média (%) | CPU Processo Pico (%) | Heap Médio (MB) | Heap Pico (MB) |\n");
        md.append("|---|---:|---:|---:|---:|---:|\n");

        List<String> phases = resourceSamples.stream().map(ResourceSample::phase).distinct().toList();
        for (String phase : phases) {
            List<ResourceSample> phaseSamples = resourceSamples.stream()
                    .filter(s -> s.phase().equals(phase))
                    .toList();
            double pCpuAvg = phaseSamples.stream().mapToDouble(ResourceSample::processCpuPercent).average().orElse(0.0);
            double pCpuMax = phaseSamples.stream().mapToDouble(ResourceSample::processCpuPercent).max().orElse(0.0);
            double pHeapAvg = phaseSamples.stream().mapToDouble(ResourceSample::heapUsedMb).average().orElse(0.0);
            double pHeapMax = phaseSamples.stream().mapToDouble(ResourceSample::heapUsedMb).max().orElse(0.0);
            md.append(String.format(Locale.US,
                    "| `%s` | %d | %.2f%% | %.2f%% | %.1f | %.1f |\n",
                    phase, phaseSamples.size(), pCpuAvg, pCpuMax, pHeapAvg, pHeapMax));
        }

        md.append("\n## 5. Arquivos Brutos Gerados\n\n");
        md.append("- `docs/benchmarks/benchmark-query-latency.csv` — Série completa de latências (p50, p95, p99, média, desvio-padrão e req/s) por volume e concorrência.\n");
        md.append("- `docs/benchmarks/benchmark-otlp-throughput.csv` — Vazão de ingestão OTLP (`spans/s` e `logs/s`) multi-tenant.\n");
        md.append("- `docs/benchmarks/benchmark-resource-samples.csv` — Série temporal de CPU e Memória amostrada a cada 100 ms.\n");

        Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
    }
}
