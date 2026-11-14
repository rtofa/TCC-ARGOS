package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.agent.InvestigationReport;
import br.com.argos.argos_api.ai.guardrail.PiiMasker;
import br.com.argos.argos_api.ai.investigation.InvestigationRepository;
import br.com.argos.argos_api.incident.IncidentQueries;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Context engineering for the assistant: the incident and the latest investigation, summarized into the system
 * message, so a follow-up question starts from what the team already knows instead of re-investigating.
 */
@Component
class AssistantContextProvider {

    private final IncidentQueries incidents;
    private final InvestigationRepository investigations;

    AssistantContextProvider(IncidentQueries incidents, InvestigationRepository investigations) {
        this.incidents = incidents;
        this.investigations = investigations;
    }

    String describe(String conversationId) {
        String[] parts = conversationId.split(":");
        UUID organizationId = UUID.fromString(parts[1]);
        UUID incidentId = UUID.fromString(parts[2]);
        StringBuilder text = new StringBuilder("# Incidente em discussão\n");
        incidents.find(organizationId, incidentId).ifPresent(incident -> text
                .append("Título: ").append(incident.title()).append('\n')
                .append("Severidade: ").append(incident.severity()).append(" | Status: ").append(incident.status()).append('\n')
                .append("Aberto em: ").append(incident.openedAt())
                .append(incident.resolvedAt() == null ? "" : " | Resolvido em: " + incident.resolvedAt()).append('\n')
                .append("Serviços afetados: ").append(incident.affectedServices().isEmpty() ? "não informados"
                        : String.join(", ", incident.affectedServices())).append('\n'));
        investigations.latestReport(organizationId, incidentId).ifPresentOrElse(report -> appendReport(text, report),
                () -> text.append("\nEste incidente ainda não tem investigação concluída.\n"));
        return PiiMasker.maskText(text.toString());
    }

    private static void appendReport(StringBuilder text, InvestigationReport report) {
        text.append("\n# Conclusão da última investigação automática\n");
        if (report.rootCause() != null) {
            text.append("Causa raiz (confiança ").append(report.rootCause().confidence()).append("): ")
                    .append(report.rootCause().summary()).append('\n');
        }
        text.append("Resumo técnico: ").append(report.technicalSummary()).append('\n');
        if (!report.nextSteps().isEmpty()) {
            text.append("Próximos passos sugeridos: ").append(String.join("; ", report.nextSteps())).append('\n');
        }
    }
}
