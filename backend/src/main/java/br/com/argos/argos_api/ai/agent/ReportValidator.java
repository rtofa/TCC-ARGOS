package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.agent.InvestigationReport.AlertExplanation;
import br.com.argos.argos_api.ai.agent.InvestigationReport.Evidence;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses the agent's JSON answer and checks it against the data the agent actually saw (grounding): a deploy, trace
 * or service cited as evidence must have appeared in the briefing or in a tool result. This is what turns
 * "plausible" into "verifiable" and catches hallucinated versions and trace ids.
 */
public final class ReportValidator {

    static final int MAX_SUMMARY = 700;
    static final int MAX_TEXT = 2500;

    private final JsonMapper json;

    public ReportValidator(JsonMapper json) {
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public Optional<InvestigationReport> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(text.substring(start, end + 1), InvestigationReport.class));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    public String toJson(InvestigationReport report) {
        return json.writeValueAsString(report);
    }

    /** Structural problems: missing fields, invalid enums, oversize text. */
    public List<String> structuralProblems(InvestigationReport report) {
        List<String> problems = new ArrayList<>();
        if (report.rootCause() == null || isBlank(report.rootCause().summary())) {
            problems.add("rootCause.summary é obrigatório");
        } else {
            if (!InvestigationReport.CONFIDENCE_LEVELS.contains(upper(report.rootCause().confidence()))) {
                problems.add("rootCause.confidence deve ser ALTA, MEDIA ou BAIXA");
            }
            if (report.rootCause().summary().length() > MAX_SUMMARY) {
                problems.add("rootCause.summary deve ter no máximo " + MAX_SUMMARY + " caracteres");
            }
        }
        if (isBlank(report.technicalSummary())) {
            problems.add("technicalSummary é obrigatório");
        } else if (report.technicalSummary().length() > MAX_TEXT) {
            problems.add("technicalSummary deve ter no máximo " + MAX_TEXT + " caracteres");
        }
        if (isBlank(report.executiveSummary())) {
            problems.add("executiveSummary é obrigatório");
        } else if (report.executiveSummary().length() > MAX_TEXT) {
            problems.add("executiveSummary deve ter no máximo " + MAX_TEXT + " caracteres");
        }
        for (Evidence evidence : report.evidence()) {
            if (!InvestigationReport.EVIDENCE_KINDS.contains(upper(evidence.kind()))) {
                problems.add("evidence.kind inválido: " + evidence.kind());
            }
        }
        report.hypotheses().stream()
                .filter(h -> !InvestigationReport.HYPOTHESIS_STATUSES.contains(upper(h.status())))
                .findFirst()
                .ifPresent(h -> problems.add("hypotheses.status deve ser CONFIRMADA, DESCARTADA ou EM_ABERTO"));
        return problems;
    }

    /** Evidence and claims that cite things the agent never saw, plus alerts it did not explain (AI-05). */
    public List<String> groundingProblems(InvestigationReport report, AgentContext context) {
        List<String> problems = new ArrayList<>();
        for (Evidence evidence : ungrounded(report, context)) {
            problems.add("a evidência \"" + AgentContext.abbreviate(evidence.description(), 80) + "\" cita \""
                    + evidence.reference() + "\", que não aparece em nenhum dado consultado");
        }
        if (report.rootCause() != null && !isBlank(report.rootCause().service())
                && !context.isKnownService(report.rootCause().service())) {
            problems.add("rootCause.service \"" + report.rootCause().service()
                    + "\" não é um serviço observado; use um destes: " + String.join(", ", context.knownServices()));
        }
        Set<String> explained = report.alertExplanations().stream()
                .map(AlertExplanation::alert)
                .filter(alert -> alert != null)
                .map(alert -> alert.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<String> missing = context.alertKeys().stream()
                .filter(key -> explained.stream().noneMatch(e -> e.contains(key.toLowerCase(Locale.ROOT))))
                .toList();
        if (!missing.isEmpty()) {
            problems.add("explique também estes alertas em alertExplanations: " + String.join(", ", missing));
        }
        return problems;
    }

    /** Evidence whose reference must be checkable (deploys and traces) but was never observed. */
    public List<Evidence> ungrounded(InvestigationReport report, AgentContext context) {
        return report.evidence().stream()
                .filter(evidence -> {
                    String kind = upper(evidence.kind());
                    boolean checkable = kind.equals("DEPLOY") || kind.equals("TRACE");
                    // A service name is a legitimate reference too ("no error traces in payments").
                    return checkable && !context.wasObserved(evidence.reference())
                            && !context.isKnownService(evidence.reference());
                })
                .toList();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String upper(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
    }
}
