package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.memory.IncidentMemory;
import br.com.argos.argos_api.alerting.IncidentAlerts;
import br.com.argos.argos_api.business.BusinessImpactService;
import br.com.argos.argos_api.alerting.IncidentAlerts.LinkedAlert;
import br.com.argos.argos_api.delivery.DeployHistory;
import br.com.argos.argos_api.delivery.DeployHistory.DeployEvent;
import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.incident.IncidentQueries.TimelineEntry;
import br.com.argos.argos_api.telemetry.TelemetryQueryService;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Context engineering: assembles the incident briefing the agent starts from. The predictable first steps of any
 * investigation (what is the incident, which alerts, what changed, how are the affected services) are done here in
 * one go, so the agent's tool calls go to drilling down instead of rediscovering basics. Each section is bounded,
 * and a failing data source degrades to a note instead of failing the investigation.
 */
@Component
public class BriefingBuilder {

    private static final Logger log = LoggerFactory.getLogger(BriefingBuilder.class);
    private static final int MAX_TIMELINE = 12;
    private static final int MAX_DEPLOYS = 8;
    private static final int MAX_HEALTH = 3;
    private static final int MAX_NODES = 6;

    public record Health(RedSeries before, RedSeries during) {
    }

    /** The rendered text for the model plus the structured data the heuristic investigator reuses. */
    public record Briefing(String text, List<LinkedAlert> alerts, List<DeployEvent> deploys, Map<String, Health> health,
                           List<TopologyNode> nodes) {
    }

    private final IncidentQueries incidents;
    private final IncidentAlerts alerts;
    private final DeployHistory deploys;
    private final TelemetryQueryService telemetry;
    private final IncidentMemory memory;
    private final BusinessImpactService business;

    public BriefingBuilder(IncidentQueries incidents, IncidentAlerts alerts, DeployHistory deploys,
                           TelemetryQueryService telemetry, IncidentMemory memory, BusinessImpactService business) {
        this.business = business;
        this.incidents = incidents;
        this.alerts = alerts;
        this.deploys = deploys;
        this.telemetry = telemetry;
        this.memory = memory;
    }

    public Briefing build(AgentContext context) {
        IncidentSnapshot incident = context.incident();
        StringBuilder text = new StringBuilder();

        text.append("## Incidente\n")
                .append("Título: ").append(incident.title()).append('\n')
                .append("Severidade: ").append(incident.severity()).append(" | Status: ").append(incident.status())
                .append(" | Origem: ").append(incident.source().equals("ALERT") ? "alerta automático" : "registro manual").append('\n')
                .append("Aberto em: ").append(TelemetryText.time(incident.openedAt()));
        if (incident.resolvedAt() != null) {
            text.append(" | Resolvido em: ").append(TelemetryText.time(incident.resolvedAt()));
        }
        text.append('\n').append("Serviços afetados: ")
                .append(incident.affectedServices().isEmpty() ? "não informados" : String.join(", ", incident.affectedServices()))
                .append('\n');
        if (incident.description() != null && !incident.description().isBlank()) {
            text.append("Descrição: ").append(context.sanitize("descricao", AgentContext.abbreviate(incident.description(), 800)))
                    .append('\n');
        }
        text.append("Janela analisada: linha de base ").append(TelemetryText.time(context.baselineFrom())).append(" a ")
                .append(TelemetryText.time(context.duringFrom())).append("; incidente ")
                .append(TelemetryText.time(context.duringFrom())).append(" a ").append(TelemetryText.time(context.duringTo()))
                .append('\n');

        List<TimelineEntry> timeline = section(text, "Linha do tempo", List.of(),
                () -> incidents.timeline(context.organizationId(), incident.id()));
        timeline.stream().skip(Math.max(0, timeline.size() - MAX_TIMELINE)).forEach(entry -> text.append("- ")
                .append(TelemetryText.time(entry.occurredAt())).append(' ').append(entry.type())
                .append(entry.newValue() == null ? "" : " → " + entry.newValue())
                .append(entry.body() == null ? "" : ": " + context.sanitize("linha_do_tempo", AgentContext.abbreviate(entry.body(), 200)))
                .append(entry.bySystem() ? " (sistema)" : "").append('\n'));

        List<LinkedAlert> linkedAlerts = section(text, "Alertas ligados ao incidente", List.of(),
                () -> alerts.forIncident(context.organizationId(), incident.id()));
        if (linkedAlerts.isEmpty()) {
            text.append("Nenhum alerta ligado (incidente manual ou sem correlação).\n");
        }
        linkedAlerts.forEach(alert -> {
            context.addAlertKey(alert.dedupKey());
            text.append("- ").append(context.sanitize("alerta", alert.dedupKey())).append(" (fonte: ").append(alert.sourceName())
                    .append(", ").append(alert.occurrences()).append(" ocorrência(s), de ")
                    .append(TelemetryText.time(alert.firstSeenAt())).append(" a ").append(TelemetryText.time(alert.lastSeenAt()))
                    .append(alert.closedAt() == null ? ", ainda ativo" : ", encerrado").append(")\n");
        });

        List<TopologyNode> nodes = section(text, "Serviços com mais erros durante o incidente", List.of(),
                () -> telemetry.topology(context.duringFrom(), context.duringTo()).nodes());
        nodes.forEach(node -> context.addKnownService(node.service()));
        List<TopologyNode> worst = nodes.stream()
                .sorted(Comparator.comparingDouble(TopologyNode::errorRatePercent).reversed()
                        .thenComparing(Comparator.comparingDouble(TopologyNode::p95DurationMs).reversed()))
                .limit(MAX_NODES)
                .toList();
        if (worst.isEmpty()) {
            text.append("Sem telemetria de traces na janela.\n");
        }
        worst.forEach(node -> text.append("- ").append(node.service()).append(": erros ")
                .append(TelemetryText.number(node.errorRatePercent())).append("%, p95 ")
                .append(TelemetryText.number(node.p95DurationMs())).append(" ms, ").append(node.spans()).append(" spans")
                .append(node.rootCause() ? " (topologia aponta como provável origem)" : "").append('\n'));

        List<String> focus = new ArrayList<>(incident.affectedServices());
        if (focus.isEmpty()) {
            worst.stream().filter(node -> node.errorSpans() > 0).map(TopologyNode::service).forEach(focus::add);
        }
        Map<String, Health> health = new LinkedHashMap<>();
        text.append("\n## Saúde dos serviços em foco\n");
        for (String service : focus.stream().distinct().limit(MAX_HEALTH).toList()) {
            try {
                Health h = new Health(telemetry.red(service, context.baselineFrom(), context.duringFrom()),
                        telemetry.red(service, context.duringFrom(), context.duringTo()));
                health.put(service, h);
                text.append(TelemetryText.health(service, h.before(), h.during())).append('\n');
            } catch (RuntimeException e) {
                log.debug("RED indisponível para {}: {}", service, e.getMessage());
                text.append("Serviço ").append(service).append(": métricas indisponíveis\n");
            }
        }
        if (focus.isEmpty()) {
            text.append("Nenhum serviço em foco: use as ferramentas para descobrir onde está a falha.\n");
        }

        List<DeployEvent> recentDeploys = section(text, "Deploys nas 24 h anteriores", List.of(),
                () -> deploys.between(context.organizationId(), incident.affectedServices(),
                        incident.openedAt().minus(Duration.ofHours(24)), context.duringTo(), MAX_DEPLOYS));
        if (recentDeploys.isEmpty()) {
            text.append("Nenhum deploy registrado").append(incident.affectedServices().isEmpty() ? "" : " nos serviços afetados")
                    .append(".\n");
        }
        recentDeploys.forEach(deploy -> {
            context.observe(deploy.version());
            context.observe(deploy.commitSha());
            text.append("- ").append(deploy.service()).append(' ').append(deploy.version()).append(" em ")
                    .append(TelemetryText.time(deploy.deployedAt())).append(" (")
                    .append(TelemetryText.relative(deploy.deployedAt(), incident.openedAt())).append(')')
                    .append(deploy.description() == null ? "" : " — \"" + context.sanitize("deploy",
                            AgentContext.abbreviate(deploy.description(), 120)) + "\"")
                    .append('\n');
        });

        String impact = section(text, "Impacto no negócio (estimativa)", "Estimativa indisponível.",
                () -> InvestigationTools.describeImpact(business.estimate(context.organizationId(), incident)));
        text.append(context.sanitize("impacto", impact)).append('\n');

        int past = section(text, "Memória", 0, () -> memory.similar(context.organizationId(), incident.id(),
                incident.affectedServices().isEmpty() ? "" : incident.affectedServices().get(0), 3).size());
        text.append(past == 0 ? "Nenhuma investigação anterior relacionada.\n"
                : past + " investigação(ões) anterior(es) relacionada(s): consulte incidentes_anteriores se for útil.\n");

        text.append("\nServiços conhecidos nos dados: ").append(String.join(", ", context.knownServices())).append('\n');
        text.append("""

                ## Tarefa
                Investigue a causa raiz deste incidente com as ferramentas, explique cada alerta ligado a ele e \
                escreva os resumos técnico e executivo. Entregue a resposta final apenas como o JSON descrito nas \
                instruções.""");
        return new Briefing(text.toString(), linkedAlerts, recentDeploys, health, nodes);
    }

    private <T> T section(StringBuilder text, String title, T fallback, Supplier<T> source) {
        text.append("\n## ").append(title).append('\n');
        try {
            return source.get();
        } catch (RuntimeException e) {
            log.warn("Seção '{}' do briefing indisponível: {}", title, e.getMessage());
            text.append("(dados indisponíveis no momento)\n");
            return fallback;
        }
    }
}
