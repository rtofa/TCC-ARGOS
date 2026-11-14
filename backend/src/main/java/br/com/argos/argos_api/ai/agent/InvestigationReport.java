package br.com.argos.argos_api.ai.agent;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The agent's final answer: root cause (AI-02), alert explanations (AI-05) and technical and executive summaries
 * (AI-01). Field names are the JSON contract described in the system prompt.
 */
public record InvestigationReport(RootCause rootCause, List<Hypothesis> hypotheses, List<Evidence> evidence,
                                  List<AlertExplanation> alertExplanations, String technicalSummary,
                                  String executiveSummary, List<String> nextSteps) {

    public static final Set<String> CONFIDENCE_LEVELS = Set.of("ALTA", "MEDIA", "BAIXA");
    public static final Set<String> EVIDENCE_KINDS =
            Set.of("DEPLOY", "METRICA", "TRACE", "LOG", "TOPOLOGIA", "ALERTA", "HISTORICO");
    public static final Set<String> HYPOTHESIS_STATUSES = Set.of("CONFIRMADA", "DESCARTADA", "EM_ABERTO");

    public record RootCause(String summary, String confidence, String service) {
    }

    public record Hypothesis(String description, String status) {
    }

    public record Evidence(String kind, String description, String reference) {
    }

    public record AlertExplanation(String alert, String explanation, String whatToCheck) {
    }

    public InvestigationReport {
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        alertExplanations = alertExplanations == null ? List.of() : List.copyOf(alertExplanations);
        nextSteps = nextSteps == null ? List.of() : List.copyOf(nextSteps);
    }

    /** Applies {@code transform} to every free-text field (used to mask personal data in the answer). */
    public InvestigationReport mapText(UnaryOperator<String> transform) {
        UnaryOperator<String> safe = text -> text == null ? null : transform.apply(text);
        return new InvestigationReport(
                rootCause == null ? null : new RootCause(safe.apply(rootCause.summary()), rootCause.confidence(),
                        rootCause.service()),
                hypotheses.stream().map(h -> new Hypothesis(safe.apply(h.description()), h.status())).toList(),
                evidence.stream().map(e -> new Evidence(e.kind(), safe.apply(e.description()), e.reference())).toList(),
                alertExplanations.stream().map(a -> new AlertExplanation(a.alert(), safe.apply(a.explanation()),
                        safe.apply(a.whatToCheck()))).toList(),
                safe.apply(technicalSummary), safe.apply(executiveSummary),
                nextSteps.stream().map(safe).toList());
    }

    public InvestigationReport withEvidence(List<Evidence> newEvidence, String confidence) {
        return new InvestigationReport(new RootCause(rootCause.summary(), confidence, rootCause.service()), hypotheses,
                newEvidence, alertExplanations, technicalSummary, executiveSummary, nextSteps);
    }
}
