package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.agent.BriefingBuilder.Briefing;
import br.com.argos.argos_api.ai.agent.BriefingBuilder.Health;
import br.com.argos.argos_api.ai.agent.InvestigationReport.AlertExplanation;
import br.com.argos.argos_api.ai.agent.InvestigationReport.Evidence;
import br.com.argos.argos_api.ai.agent.InvestigationReport.Hypothesis;
import br.com.argos.argos_api.ai.agent.InvestigationReport.RootCause;
import br.com.argos.argos_api.delivery.DeployHistory.DeployEvent;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Last stop of the routing chain: a deterministic investigation from the briefing data, used when no model is
 * configured or every model failed. It never claims more than the rules can support (confidence at most MEDIA),
 * but the incident still gets a first analysis instead of nothing.
 */
@Component
public class HeuristicInvestigator {

    static final Duration SUSPICIOUS_DEPLOY_WINDOW = Duration.ofHours(2);

    public InvestigationReport investigate(AgentContext context, Briefing briefing, String reason) {
        List<Evidence> evidence = new ArrayList<>();
        List<Hypothesis> hypotheses = new ArrayList<>();

        Optional<DeployEvent> suspectDeploy = briefing.deploys().stream()
                .filter(deploy -> !deploy.deployedAt().isAfter(context.incident().openedAt()))
                .filter(deploy -> Duration.between(deploy.deployedAt(), context.incident().openedAt())
                        .compareTo(SUSPICIOUS_DEPLOY_WINDOW) <= 0)
                .max(Comparator.comparing(DeployEvent::deployedAt));
        suspectDeploy.ifPresent(deploy -> {
            evidence.add(new Evidence("DEPLOY", "Deploy de " + deploy.service() + " " + deploy.version() + " "
                    + TelemetryText.relative(deploy.deployedAt(), context.incident().openedAt()), deploy.version()));
            hypotheses.add(new Hypothesis("Regressão introduzida pelo deploy " + deploy.version() + " de " + deploy.service(),
                    "EM_ABERTO"));
        });

        Optional<Map.Entry<String, Health>> worstDegradation = briefing.health().entrySet().stream()
                .filter(entry -> entry.getValue().during().totalRequests() > 0)
                .max(Comparator.comparingDouble(entry -> entry.getValue().during().errorRatePercent()
                        - entry.getValue().before().errorRatePercent()));
        worstDegradation.ifPresent(entry -> {
            Health health = entry.getValue();
            evidence.add(new Evidence("METRICA", entry.getKey() + ": erros de "
                    + TelemetryText.number(health.before().errorRatePercent()) + "% para "
                    + TelemetryText.number(health.during().errorRatePercent()) + "%, p95 de "
                    + TelemetryText.number(health.before().p95DurationMs()) + " ms para "
                    + TelemetryText.number(health.during().p95DurationMs()) + " ms", entry.getKey()));
        });

        Optional<TopologyNode> topologySuspect = briefing.nodes().stream().filter(TopologyNode::rootCause).findFirst();
        topologySuspect.ifPresent(node -> {
            evidence.add(new Evidence("TOPOLOGIA", "A topologia aponta " + node.service() + " como provável origem ("
                    + TelemetryText.number(node.errorRatePercent()) + "% de erros)", node.service()));
            hypotheses.add(new Hypothesis("Falha originada em " + node.service() + " e propagada para quem depende dele",
                    "EM_ABERTO"));
        });

        String service = suspectDeploy.map(DeployEvent::service)
                .or(() -> topologySuspect.map(TopologyNode::service))
                .or(() -> worstDegradation.map(Map.Entry::getKey))
                .orElse(null);
        String confidence = suspectDeploy.isPresent() && (topologySuspect.isPresent() || worstDegradation.isPresent())
                ? "MEDIA" : "BAIXA";
        String summary = suspectDeploy
                .map(deploy -> "Provável regressão do deploy " + deploy.version() + " de " + deploy.service()
                        + ", feito " + TelemetryText.relative(deploy.deployedAt(), context.incident().openedAt()) + ".")
                .or(() -> topologySuspect.map(node -> "Falha concentrada em " + node.service()
                        + ", apontado pela topologia como origem dos erros."))
                .or(() -> worstDegradation.map(entry -> "Degradação em " + entry.getKey() + " sem mudança registrada que a explique."))
                .orElse("Não há dados suficientes para apontar uma causa: sem deploys recentes nem telemetria de erros na janela.");

        List<AlertExplanation> alerts = briefing.alerts().stream()
                .map(alert -> new AlertExplanation(alert.dedupKey(),
                        "Alerta de " + alert.sourceName() + " disparado " + alert.occurrences() + " vez(es) desde "
                                + TelemetryText.time(alert.firstSeenAt()) + (alert.closedAt() == null ? "; ainda ativo." : "; já encerrado."),
                        "Confira as métricas do serviço citado no alerta e compare com o horário do disparo."))
                .toList();

        String technical = summary + " Evidências: " + (evidence.isEmpty() ? "nenhuma" : evidence.stream()
                .map(Evidence::description).reduce((a, b) -> a + "; " + b).orElse("")) + ". (Análise heurística: " + reason + ".)";
        String executive = "O incidente \"" + context.incident().title() + "\" (" + context.incident().severity()
                + ") está em análise. " + (service == null ? "A origem ainda não foi identificada." : "A suspeita principal é o serviço " + service + ".")
                + " Esta é uma análise automática por regras, sem IA, e deve ser confirmada pela equipe.";

        List<String> nextSteps = new ArrayList<>();
        suspectDeploy.ifPresent(deploy -> nextSteps.add("Avaliar rollback de " + deploy.service() + " " + deploy.version()));
        nextSteps.add("Revisar traces com erro e logs do serviço suspeito");
        nextSteps.add("Configurar a chave da IA para obter uma investigação completa");

        return new InvestigationReport(new RootCause(summary, confidence, service), hypotheses, evidence, alerts,
                technical, executive, nextSteps);
    }
}
