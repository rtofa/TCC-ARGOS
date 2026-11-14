package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.guardrail.PiiMasker;
import br.com.argos.argos_api.ai.guardrail.PromptInjectionDetector;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * State of one agent run, bound to the worker thread: the tenant and incident being analysed, the analysis windows,
 * the facts the agent has actually seen (used by the grounding guardrail), the steps it took and the guardrail events.
 *
 * <p>Tools are stateless beans and read the organization from here, never from model-provided arguments, so the
 * model cannot query another tenant's data however it is prompted.
 */
public final class AgentContext implements AutoCloseable {

    /** Telemetry from a little before the incident opened belongs to "during": the alert fires after the failure starts. */
    static final Duration DETECTION_LEAD = Duration.ofMinutes(10);
    static final Duration BASELINE_LENGTH = Duration.ofMinutes(60);
    static final Duration MAX_ANALYSIS_LENGTH = Duration.ofHours(6);
    static final int MAX_TOOL_RESULT_CHARS = 3500;

    public record Step(int order, String tool, String arguments, String summary, long durationMs, boolean error) {
    }

    public record GuardrailEvent(String type, String detail) {
    }

    private static final ThreadLocal<AgentContext> CURRENT = new ThreadLocal<>();

    private final UUID organizationId;
    private final IncidentSnapshot incident;
    private final Instant duringFrom;
    private final Instant duringTo;
    private final Instant baselineFrom;
    private final int toolBudget;

    private final Set<String> knownServices = new LinkedHashSet<>();
    private final Set<String> observedReferences = new LinkedHashSet<>();
    private final Set<String> alertKeys = new LinkedHashSet<>();
    private final List<Step> steps = new ArrayList<>();
    private final List<GuardrailEvent> guardrailEvents = new ArrayList<>();
    private int toolCalls;
    private InvestigationReport report;
    private InvestigationReport lastCandidate;

    private AgentContext(UUID organizationId, IncidentSnapshot incident, Instant now, int toolBudget) {
        this.organizationId = organizationId;
        this.incident = incident;
        Instant end = incident.resolvedAt() != null ? incident.resolvedAt() : now;
        Instant cap = incident.openedAt().plus(MAX_ANALYSIS_LENGTH);
        this.duringFrom = incident.openedAt().minus(DETECTION_LEAD);
        this.duringTo = end.isAfter(cap) ? cap : end.isBefore(incident.openedAt()) ? incident.openedAt() : end;
        this.baselineFrom = duringFrom.minus(BASELINE_LENGTH);
        this.toolBudget = toolBudget;
        incident.affectedServices().forEach(this::addKnownService);
    }

    public static AgentContext open(UUID organizationId, IncidentSnapshot incident, Instant now, int toolBudget) {
        AgentContext context = new AgentContext(organizationId, incident, now, toolBudget);
        CURRENT.set(context);
        return context;
    }

    public static AgentContext current() {
        AgentContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("Ferramenta de IA chamada fora de uma execução de agente");
        }
        return context;
    }

    public static Optional<AgentContext> currentIfAny() {
        return Optional.ofNullable(CURRENT.get());
    }

    @Override
    public void close() {
        CURRENT.remove();
    }

    /**
     * Runs one tool call: enforces the budget, times it, turns errors into text the model can react to, masks
     * personal data, defuses injected instructions, truncates, and wraps the result as untrusted data.
     */
    public synchronized String tool(String name, String arguments, Supplier<String> body) {
        if (toolCalls >= toolBudget) {
            steps.add(new Step(steps.size() + 1, name, arguments, "orçamento de ferramentas esgotado", 0, true));
            return "Orçamento de chamadas de ferramenta esgotado (" + toolBudget + "). Conclua o relatório com as "
                    + "evidências que você já tem e indique o que ficou sem verificar.";
        }
        toolCalls++;
        long started = System.nanoTime();
        String raw;
        boolean error = false;
        try {
            raw = body.get();
        } catch (RuntimeException e) {
            error = true;
            raw = "Erro ao consultar os dados: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String safe = sanitize(name, raw);
        steps.add(new Step(steps.size() + 1, name, arguments, firstLine(safe), elapsed, error));
        return "<dados fonte=\"" + name + "\" confiavel=\"nao\">\n" + safe + "\n</dados>";
    }

    /** Masks, defuses and truncates untrusted text; records what the guardrails did. */
    public synchronized String sanitize(String source, String raw) {
        PiiMasker.Masked masked = PiiMasker.mask(raw == null ? "" : raw);
        if (masked.changed()) {
            guardrailEvents.add(new GuardrailEvent("PII_MASCARADA", source + ": " + masked.counts()));
        }
        String text = masked.text();
        PromptInjectionDetector.detect(text).ifPresent(fragment -> guardrailEvents.add(
                new GuardrailEvent("INJECAO_NEUTRALIZADA", source + ": \"" + abbreviate(fragment, 60) + "\"")));
        text = PromptInjectionDetector.neutralize(text);
        if (text.length() > MAX_TOOL_RESULT_CHARS) {
            text = text.substring(0, MAX_TOOL_RESULT_CHARS) + "\n… (resultado truncado em " + MAX_TOOL_RESULT_CHARS
                    + " caracteres)";
        }
        return text;
    }

    public synchronized void guardrail(String type, String detail) {
        guardrailEvents.add(new GuardrailEvent(type, abbreviate(detail, 300)));
    }

    public synchronized void addKnownService(String service) {
        if (service != null && !service.isBlank()) {
            knownServices.add(service.trim());
        }
    }

    public synchronized void addKnownServices(Collection<String> services) {
        services.forEach(this::addKnownService);
    }

    /** Deploy versions, commit SHAs and trace ids the agent has seen; evidence may only cite these. */
    public synchronized void observe(String reference) {
        if (reference != null && !reference.isBlank()) {
            observedReferences.add(reference.trim().toLowerCase(Locale.ROOT));
        }
    }

    public synchronized void addAlertKey(String dedupKey) {
        if (dedupKey != null) {
            alertKeys.add(dedupKey);
        }
    }

    /** Resolves a model-provided service name against the services seen in the data, ignoring case. */
    public synchronized Optional<String> resolveService(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return knownServices.stream().filter(service -> service.equalsIgnoreCase(wanted)).findFirst();
    }

    public synchronized boolean isKnownService(String name) {
        return resolveService(name).isPresent();
    }

    public synchronized boolean wasObserved(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        String wanted = reference.trim().toLowerCase(Locale.ROOT);
        return observedReferences.stream().anyMatch(seen -> seen.equals(wanted)
                || (wanted.length() >= 7 && seen.startsWith(wanted)) || wanted.contains(seen) && seen.length() >= 5);
    }

    public synchronized void acceptReport(InvestigationReport report) {
        this.report = report;
    }

    public synchronized void rememberCandidate(InvestigationReport candidate) {
        this.lastCandidate = candidate;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public IncidentSnapshot incident() {
        return incident;
    }

    public Instant duringFrom() {
        return duringFrom;
    }

    public Instant duringTo() {
        return duringTo;
    }

    public Instant baselineFrom() {
        return baselineFrom;
    }

    public synchronized List<String> knownServices() {
        return List.copyOf(knownServices);
    }

    public synchronized Set<String> alertKeys() {
        return Set.copyOf(alertKeys);
    }

    public synchronized List<Step> steps() {
        return List.copyOf(steps);
    }

    public synchronized List<GuardrailEvent> guardrailEvents() {
        return List.copyOf(guardrailEvents);
    }

    public synchronized int toolCalls() {
        return toolCalls;
    }

    public synchronized Optional<InvestigationReport> report() {
        return Optional.ofNullable(report);
    }

    public synchronized Optional<InvestigationReport> lastCandidate() {
        return Optional.ofNullable(lastCandidate);
    }

    static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return abbreviate(line, 160);
    }

    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
