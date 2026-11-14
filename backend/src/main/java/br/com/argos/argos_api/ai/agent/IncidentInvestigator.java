package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.AiProperties;
import br.com.argos.argos_api.ai.agent.BriefingBuilder.Briefing;
import br.com.argos.argos_api.ai.agent.InvestigationReport.Evidence;
import br.com.argos.argos_api.ai.guardrail.PiiMasker;
import br.com.argos.argos_api.ai.llm.AllRoutesFailedException;
import br.com.argos.argos_api.ai.llm.LlmCallLog;
import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one investigation: builds the briefing, lets the agent investigate with its tools behind the output
 * guardrail, and degrades step by step instead of failing:
 * <ol>
 *   <li>the routed models (primary, then fallbacks);</li>
 *   <li>if the guardrail rejected every attempt, the last answer minus the evidence it could not ground;</li>
 *   <li>if no model is available, the heuristic investigator.</li>
 * </ol>
 */
@Component
public class IncidentInvestigator {

    private static final Logger log = LoggerFactory.getLogger(IncidentInvestigator.class);

    private final InvestigatorAgent agent;
    private final LlmRouter router;
    private final BriefingBuilder briefingBuilder;
    private final HeuristicInvestigator heuristic;
    private final ReportValidator validator;
    private final AiProperties properties;
    private final Clock clock;

    public IncidentInvestigator(InvestigatorAgent agent, @Qualifier("investigationRouter") LlmRouter router,
                                BriefingBuilder briefingBuilder, HeuristicInvestigator heuristic,
                                ReportValidator validator, AiProperties properties, Clock clock) {
        this.agent = agent;
        this.router = router;
        this.briefingBuilder = briefingBuilder;
        this.heuristic = heuristic;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
    }

    /** Must run inside the organization's security scope (see {@code SystemActor}). */
    public InvestigationOutcome investigate(UUID investigationId, IncidentSnapshot incident) {
        try (AgentContext context = AgentContext.open(incident.organizationId(), incident, clock.instant(),
                properties.getMaxToolCalls())) {
            Briefing briefing = briefingBuilder.build(context);
            if (!router.available()) {
                return heuristic(context, briefing, "IA não configurada (defina GROQ_API_KEY no backend/.env)", List.of());
            }
            String memoryId = investigationId.toString();
            LlmCallLog.start();
            try {
                Result<String> result = agent.investigate(memoryId, briefing.text());
                InvestigationReport report = context.report()
                        .or(() -> validator.parse(result.content()))
                        .orElseThrow(() -> new IllegalStateException("O agente terminou sem relatório"));
                return outcome("AI", report, context, LlmCallLog.stop(), null);
            } catch (OutputGuardrailException e) {
                List<LlmCall> calls = LlmCallLog.stop();
                Optional<InvestigationReport> salvaged = salvage(context);
                if (salvaged.isPresent()) {
                    return outcome("AI", salvaged.get(), context, calls,
                            "Relatório aceito com ajustes: evidências não verificáveis foram removidas");
                }
                return heuristic(context, briefing, "a IA não produziu um relatório válido", calls);
            } catch (AllRoutesFailedException e) {
                log.warn("Investigação {} sem modelo disponível: {}", investigationId, e.getMessage());
                return heuristic(context, briefing, "modelos de IA indisponíveis", LlmCallLog.stop());
            } catch (AuthenticationException e) {
                log.error("Chave da API de IA rejeitada: {}", e.getMessage());
                return heuristic(context, briefing, "chave da API de IA rejeitada", LlmCallLog.stop());
            } catch (RuntimeException e) {
                log.error("Investigação {} falhou na IA", investigationId, e);
                return heuristic(context, briefing, "erro na IA: " + AgentContext.abbreviate(e.getMessage(), 120),
                        LlmCallLog.stop());
            } finally {
                agent.evictChatMemory(memoryId);
                LlmCallLog.stop();
            }
        }
    }

    /** Keeps the last structurally valid answer, drops evidence it could not ground and caps confidence. */
    private Optional<InvestigationReport> salvage(AgentContext context) {
        return context.lastCandidate()
                .filter(candidate -> validator.structuralProblems(candidate).isEmpty())
                .map(candidate -> {
                    List<Evidence> ungrounded = validator.ungrounded(candidate, context);
                    List<Evidence> kept = candidate.evidence().stream().filter(e -> !ungrounded.contains(e)).toList();
                    context.guardrail("EVIDENCIAS_REMOVIDAS", ungrounded.size() + " evidência(s) sem lastro nos dados");
                    return candidate.withEvidence(kept, "BAIXA").mapText(PiiMasker::maskText);
                });
    }

    private InvestigationOutcome heuristic(AgentContext context, Briefing briefing, String reason, List<LlmCall> calls) {
        InvestigationReport report = heuristic.investigate(context, briefing, reason);
        return outcome("HEURISTIC", report, context, calls, "Análise heurística: " + reason);
    }

    private static InvestigationOutcome outcome(String mode, InvestigationReport report, AgentContext context,
                                                List<LlmCall> calls, String note) {
        return new InvestigationOutcome(mode, report, context.steps(), context.guardrailEvents(), calls,
                context.toolCalls(), note);
    }
}
