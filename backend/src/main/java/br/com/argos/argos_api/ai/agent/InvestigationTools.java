package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.memory.IncidentMemory;
import br.com.argos.argos_api.ai.memory.IncidentMemory.PastIncident;
import br.com.argos.argos_api.business.BusinessImpactService;
import br.com.argos.argos_api.business.BusinessImpactService.IncidentImpact;
import br.com.argos.argos_api.delivery.DeployHistory;
import br.com.argos.argos_api.delivery.DeployHistory.DeployEvent;
import br.com.argos.argos_api.telemetry.TelemetryQueryService;
import br.com.argos.argos_api.telemetry.TelemetryViews.LogView;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceTopology;
import br.com.argos.argos_api.telemetry.TelemetryViews.SpanView;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyEdge;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyNode;
import br.com.argos.argos_api.telemetry.TelemetryViews.TraceDetail;
import br.com.argos.argos_api.telemetry.TelemetryViews.TraceSummary;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only tools of the investigator and of the assistant. Every tool:
 * <ul>
 *   <li>takes the organization and the time windows from {@link AgentContext}, never from the model;</li>
 *   <li>validates and clamps the model's arguments (unknown service names get the list of known ones back);</li>
 *   <li>returns compact, pre-aggregated text, masked and wrapped as untrusted data by {@link AgentContext#tool}.</li>
 * </ul>
 */
@Component
public class InvestigationTools {

    private static final int MAX_DEPLOYS = 15;
    private static final int MAX_TRACES = 8;
    private static final int MAX_SPANS = 14;
    private static final int MAX_LOG_GROUPS = 12;

    private final TelemetryQueryService telemetry;
    private final DeployHistory deploys;
    private final IncidentMemory memory;
    private final BusinessImpactService business;

    public InvestigationTools(TelemetryQueryService telemetry, DeployHistory deploys, IncidentMemory memory,
                              BusinessImpactService business) {
        this.telemetry = telemetry;
        this.deploys = deploys;
        this.memory = memory;
        this.business = business;
    }

    @Tool(name = "saude_do_servico", value = "Métricas RED de um serviço (requisições/min, % de erros, latência p50/p95/p99), "
            + "comparando a hora anterior ao incidente (linha de base) com o período do incidente, e o pico de erros.")
    public String serviceHealth(@P(name = "servico", value = "Nome do serviço, como aparece nos dados") String service) {
        AgentContext context = AgentContext.current();
        return context.tool("saude_do_servico", "servico=" + service, () -> {
            String resolved = context.resolveService(service).orElse(null);
            if (resolved == null) {
                return unknownService(context, service);
            }
            return TelemetryText.health(resolved,
                    telemetry.red(resolved, context.baselineFrom(), context.duringFrom()),
                    telemetry.red(resolved, context.duringFrom(), context.duringTo()));
        });
    }

    @Tool(name = "deploys_recentes", value = "Deploys registrados antes da abertura do incidente e durante ele. "
            + "Use para responder \"o que mudou?\" e correlacionar mudanças com o início da falha.")
    public String recentDeploys(
            @P(name = "servico", value = "Serviço; vazio para todos os serviços", required = false) String service,
            @P(name = "horasAntes", value = "Quantas horas antes da abertura do incidente olhar (1 a 72)") int hoursBefore) {
        AgentContext context = AgentContext.current();
        return context.tool("deploys_recentes", "servico=" + service + ", horas=" + hoursBefore, () -> {
            List<String> services = List.of();
            if (service != null && !service.isBlank()) {
                String resolved = context.resolveService(service).orElse(null);
                if (resolved == null) {
                    return unknownService(context, service);
                }
                services = List.of(resolved);
            }
            int hours = Math.max(1, Math.min(hoursBefore, 72));
            Instant opening = context.incident().openedAt();
            List<DeployEvent> found = deploys.between(context.organizationId(), services,
                    opening.minus(Duration.ofHours(hours)), context.duringTo(), MAX_DEPLOYS);
            if (found.isEmpty()) {
                return "Nenhum deploy registrado nas " + hours + " h anteriores nem durante o incidente.";
            }
            StringBuilder out = new StringBuilder(found.size() + " deploy(s), do mais recente para o mais antigo:\n");
            for (DeployEvent deploy : found) {
                context.observe(deploy.version());
                context.observe(deploy.commitSha());
                context.addKnownService(deploy.service());
                out.append("- ").append(TelemetryText.time(deploy.deployedAt())).append(" (")
                        .append(TelemetryText.relative(deploy.deployedAt(), opening)).append(") ")
                        .append(deploy.service()).append(' ').append(deploy.version())
                        .append(" [").append(deploy.environment()).append(']');
                if (deploy.commitSha() != null) {
                    out.append(" commit ").append(deploy.commitSha(), 0, Math.min(12, deploy.commitSha().length()));
                }
                if (deploy.author() != null) {
                    out.append(" por ").append(deploy.author());
                }
                if (deploy.description() != null && !deploy.description().isBlank()) {
                    out.append(" — \"").append(AgentContext.abbreviate(deploy.description(), 140)).append('"');
                }
                out.append('\n');
            }
            return out.toString();
        });
    }

    @Tool(name = "dependencias_do_servico", value = "Quem chama o serviço (upstream) e quem ele chama (downstream) durante "
            + "o incidente, com chamadas, % de erros e p95 de cada ligação. Use para saber se a falha veio de uma "
            + "dependência ou se propagou para quem depende do serviço.")
    public String serviceDependencies(@P(name = "servico", value = "Nome do serviço, como aparece nos dados") String service) {
        AgentContext context = AgentContext.current();
        return context.tool("dependencias_do_servico", "servico=" + service, () -> {
            ServiceTopology topology = telemetry.topology(context.duringFrom(), context.duringTo());
            topology.nodes().forEach(node -> context.addKnownService(node.service()));
            String resolved = context.resolveService(service).orElse(null);
            if (resolved == null) {
                return unknownService(context, service);
            }
            StringBuilder out = new StringBuilder();
            topology.nodes().stream().filter(node -> node.service().equals(resolved)).findFirst()
                    .ifPresent(node -> out.append(node(node)).append('\n'));
            List<TopologyEdge> upstream = topology.edges().stream().filter(e -> e.target().equals(resolved)).toList();
            List<TopologyEdge> downstream = topology.edges().stream().filter(e -> e.source().equals(resolved)).toList();
            out.append("Quem chama ").append(resolved).append(" (upstream):").append(upstream.isEmpty() ? " ninguém nos dados\n" : "\n");
            upstream.forEach(edge -> out.append("- ").append(edge(edge.source(), edge)).append('\n'));
            out.append(resolved).append(" chama (downstream):").append(downstream.isEmpty() ? " nenhum serviço nos dados\n" : "\n");
            downstream.forEach(edge -> out.append("- ").append(edge(edge.target(), edge)).append('\n'));
            List<String> suspects = topology.nodes().stream().filter(TopologyNode::rootCause).map(TopologyNode::service).toList();
            if (!suspects.isEmpty()) {
                out.append("A análise de topologia marca como provável origem: ").append(String.join(", ", suspects));
            }
            return out.toString();
        });
    }

    @Tool(name = "traces_com_erro", value = "Traces com erro de um serviço durante o incidente: duração, serviço raiz, "
            + "quantidade de spans com erro e serviços envolvidos. Use o traceId em detalhe_do_trace.")
    public String errorTraces(@P(name = "servico", value = "Nome do serviço, como aparece nos dados") String service,
                              @P(name = "limite", value = "Quantos traces trazer (1 a 8)") int limit) {
        AgentContext context = AgentContext.current();
        return context.tool("traces_com_erro", "servico=" + service + ", limite=" + limit, () -> {
            String resolved = context.resolveService(service).orElse(null);
            if (resolved == null) {
                return unknownService(context, service);
            }
            List<TraceSummary> traces = telemetry.traces(resolved, true, null, context.duringFrom(), context.duringTo(),
                    Math.max(1, Math.min(limit, MAX_TRACES)));
            if (traces.isEmpty()) {
                return "Nenhum trace com erro em " + resolved + " durante o incidente.";
            }
            StringBuilder out = new StringBuilder();
            for (TraceSummary trace : traces) {
                context.observe(trace.traceId());
                context.addKnownServices(trace.services());
                out.append("- ").append(trace.traceId()).append(" às ").append(TelemetryText.time(trace.startTime()))
                        .append(": ").append(trace.rootService()).append(' ').append(trace.rootName())
                        .append(", ").append(TelemetryText.number(trace.durationMs())).append(" ms, ")
                        .append(trace.errorCount()).append('/').append(trace.spanCount()).append(" spans com erro, serviços: ")
                        .append(String.join(" → ", trace.services())).append('\n');
            }
            return out.toString();
        });
    }

    @Tool(name = "detalhe_do_trace", value = "Spans de um trace: onde está o erro (mensagem de status) e onde o tempo "
            + "foi gasto. Mostra os spans com erro e os mais lentos.")
    public String traceDetail(@P(name = "traceId", value = "traceId obtido em traces_com_erro") String traceId) {
        AgentContext context = AgentContext.current();
        return context.tool("detalhe_do_trace", "traceId=" + traceId, () -> {
            TraceDetail trace = telemetry.trace(traceId == null ? "" : traceId.trim());
            context.observe(trace.traceId());
            List<SpanView> relevant = trace.spans().stream()
                    .sorted(Comparator.comparing(SpanView::error).reversed()
                            .thenComparing(Comparator.comparingDouble(SpanView::durationMs).reversed()))
                    .limit(MAX_SPANS)
                    .toList();
            StringBuilder out = new StringBuilder("Trace " + trace.traceId() + ", " + TelemetryText.number(trace.durationMs())
                    + " ms, " + trace.spans().size() + " spans (mostrando os com erro e os mais lentos):\n");
            for (SpanView span : relevant) {
                context.addKnownService(span.service());
                out.append("- ").append(span.error() ? "[ERRO] " : "").append(span.service()).append(' ')
                        .append(span.name()).append(", ").append(TelemetryText.number(span.durationMs())).append(" ms");
                if (span.statusMessage() != null && !span.statusMessage().isBlank()) {
                    out.append(", status: \"").append(AgentContext.abbreviate(span.statusMessage(), 200)).append('"');
                }
                out.append('\n');
            }
            return out.toString();
        });
    }

    @Tool(name = "logs_de_erro", value = "Logs de nível WARN ou maior de um serviço durante o incidente, agrupados por "
            + "mensagem (números e ids viram #), com a contagem de cada grupo e um exemplo.")
    public String errorLogs(@P(name = "servico", value = "Nome do serviço, como aparece nos dados") String service,
                            @P(name = "texto", value = "Texto para filtrar as mensagens; vazio para todas", required = false) String text) {
        AgentContext context = AgentContext.current();
        return context.tool("logs_de_erro", "servico=" + service + ", texto=" + text, () -> {
            String resolved = context.resolveService(service).orElse(null);
            if (resolved == null) {
                return unknownService(context, service);
            }
            List<LogView> logs = telemetry.logs(resolved, "WARN", null, text == null || text.isBlank() ? null : text,
                    context.duringFrom(), context.duringTo(), 300);
            if (logs.isEmpty()) {
                return "Nenhum log WARN/ERROR em " + resolved + " durante o incidente.";
            }
            Map<String, List<LogView>> groups = logs.stream().collect(Collectors.groupingBy(
                    log -> template(log.body()), LinkedHashMap::new, Collectors.toList()));
            StringBuilder out = new StringBuilder(logs.size() + " logs em " + groups.size() + " grupo(s):\n");
            groups.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, List<LogView>> e) -> e.getValue().size()).reversed())
                    .limit(MAX_LOG_GROUPS)
                    .forEach(group -> {
                        LogView example = group.getValue().get(0);
                        if (example.traceId() != null) {
                            context.observe(example.traceId());
                        }
                        out.append("- ").append(group.getValue().size()).append("x [").append(example.severityText())
                                .append("] \"").append(AgentContext.abbreviate(example.body(), 220)).append('"');
                        if (example.traceId() != null) {
                            out.append(" (trace ").append(example.traceId()).append(')');
                        }
                        out.append('\n');
                    });
            return out.toString();
        });
    }

    @Tool(name = "incidentes_anteriores", value = "Memória de longo prazo: o que foi concluído em incidentes anteriores "
            + "desta organização que afetaram o serviço. Use para reconhecer falhas recorrentes.")
    public String similarPastIncidents(@P(name = "servico", value = "Serviço; vazio para os incidentes mais recentes", required = false) String service) {
        AgentContext context = AgentContext.current();
        return context.tool("incidentes_anteriores", "servico=" + service, () -> {
            List<PastIncident> past = memory.similar(context.organizationId(), context.incident().id(), service, 3);
            if (past.isEmpty()) {
                return "Nenhuma investigação anterior registrada" + (service == null || service.isBlank() ? "." : " para " + service + ".");
            }
            StringBuilder out = new StringBuilder();
            for (PastIncident incident : past) {
                out.append("- ").append(TelemetryText.time(incident.openedAt())).append(" \"").append(incident.title())
                        .append("\": causa ").append(incident.confidence() == null ? "" : "(" + incident.confidence() + ") ")
                        .append(AgentContext.abbreviate(incident.rootCause(), 240));
                if (incident.nextSteps() != null) {
                    out.append(" | ações sugeridas: ").append(AgentContext.abbreviate(incident.nextSteps(), 200));
                }
                out.append('\n');
            }
            return out.toString();
        });
    }

    @Tool(name = "impacto_no_negocio", value = "Impacto estimado do incidente nos KPIs de negócio da organização "
            + "(pedidos, receita etc.): unidades e reais perdidos até agora, por KPI. Use no resumo executivo.")
    public String businessImpact(
            // Gemini rejects function declarations without parameters, so the tool takes an optional filter.
            @P(name = "kpi", value = "Nome de um KPI para ver só ele; vazio para todos", required = false) String kpi) {
        AgentContext context = AgentContext.current();
        return context.tool("impacto_no_negocio", "kpi=" + kpi, () -> {
            IncidentImpact impact = business.estimate(context.organizationId(), context.incident());
            if (kpi != null && !kpi.isBlank()) {
                impact = new IncidentImpact(impact.incidentId(), impact.ongoing(), impact.minutes(), impact.method(),
                        impact.kpis().stream().filter(k -> k.kpi().equalsIgnoreCase(kpi.trim())).toList(),
                        impact.totalValue(), impact.currency());
            }
            return describeImpact(impact);
        });
    }

    static String describeImpact(IncidentImpact impact) {
        if (impact.kpis().isEmpty()) {
            return "Nenhum KPI de negócio está ligado aos serviços afetados; não há estimativa de impacto.";
        }
        StringBuilder out = new StringBuilder("Impacto estimado em " + impact.minutes() + " min"
                + (impact.ongoing() ? " (incidente em andamento, o valor continua subindo)" : "") + ", método "
                + (impact.method().equals("TELEMETRIA") ? "taxa de erro real medida na telemetria" : "fator da severidade")
                + ":\n");
        impact.kpis().forEach(kpi -> out.append("- ").append(kpi.kpi()).append(": ~").append(kpi.lostUnits().toPlainString())
                .append(' ').append(kpi.unit()).append(" perdidos")
                .append(kpi.lostValue() == null ? "" : " (R$ " + kpi.lostValue().toPlainString() + ")")
                .append(", degradação de ").append(kpi.degradationPercent().toPlainString()).append("% via ")
                .append(kpi.service()).append('\n'));
        out.append("Total estimado: R$ ").append(impact.totalValue().toPlainString());
        return out.toString();
    }

    private static String node(TopologyNode node) {
        return node.service() + ": " + node.spans() + " spans, erros " + TelemetryText.number(node.errorRatePercent())
                + "%, p95 " + TelemetryText.number(node.p95DurationMs()) + " ms" + (node.rootCause() ? " (provável origem)" : "");
    }

    private static String edge(String other, TopologyEdge edge) {
        return other + ": " + edge.calls() + " chamadas, erros " + TelemetryText.number(edge.errorRatePercent())
                + "%, p95 " + TelemetryText.number(edge.p95DurationMs()) + " ms";
    }

    private static String unknownService(AgentContext context, String service) {
        return "Serviço \"" + service + "\" não encontrado nos dados. Serviços conhecidos: "
                + String.join(", ", context.knownServices());
    }

    /** Groups log lines that differ only in ids, numbers and quoted values. */
    static String template(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}", "#")
                .replaceAll("\\b[0-9a-fA-F]{16,}\\b", "#")
                .replaceAll("\\d+", "#")
                .replaceAll("\"[^\"]{0,80}\"", "\"#\"")
                .trim();
    }
}
