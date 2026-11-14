package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.agent.AgentContext;
import br.com.argos.argos_api.ai.agent.AssistantAgent;
import br.com.argos.argos_api.ai.assistant.ChatMessageRepository;
import br.com.argos.argos_api.ai.assistant.ChatMessageRepository.ChatMessageRow;
import br.com.argos.argos_api.ai.guardrail.AssistantGuardrails;
import br.com.argos.argos_api.ai.guardrail.PromptInjectionDetector;
import br.com.argos.argos_api.ai.llm.AllRoutesFailedException;
import br.com.argos.argos_api.ai.llm.LlmCallLog;
import br.com.argos.argos_api.ai.llm.LlmCallLog.LlmCall;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.ai.memory.JdbcChatMemoryStore;
import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import br.com.argos.argos_api.shared.security.SystemActor;
import br.com.argos.argos_api.telemetry.TelemetryQueryService;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyNode;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.service.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Follow-up questions about an incident. Asking is asynchronous: the question and a pending answer are stored, a
 * bounded pool runs the agent, and the console polls the conversation until the answer is ready.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final ChatMessageRepository messages;
    private final AssistantAgent agent;
    private final LlmRouter router;
    private final IncidentQueries incidents;
    private final TelemetryQueryService telemetry;
    private final CurrentUser currentUser;
    private final ThreadPoolTaskExecutor executor;
    private final AiProperties properties;
    private final Clock clock;

    public AssistantService(ChatMessageRepository messages, AssistantAgent agent,
                            @Qualifier("assistantRouter") LlmRouter router, IncidentQueries incidents,
                            TelemetryQueryService telemetry, CurrentUser currentUser,
                            @Qualifier("assistantExecutor") ThreadPoolTaskExecutor executor, AiProperties properties,
                            Clock clock) {
        this.messages = messages;
        this.agent = agent;
        this.router = router;
        this.incidents = incidents;
        this.telemetry = telemetry;
        this.currentUser = currentUser;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    public List<ChatMessageRow> conversation(UUID incidentId) {
        UUID organizationId = currentUser.get().organizationId();
        requireIncident(organizationId, incidentId);
        return messages.list(organizationId, incidentId, 100);
    }

    public List<ChatMessageRow> ask(UUID incidentId, String question) {
        AuthenticatedUser user = currentUser.get();
        String text = question == null ? "" : question.strip();
        if (text.isEmpty() || text.length() > AssistantGuardrails.MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("A pergunta deve ter entre 1 e "
                    + AssistantGuardrails.MAX_QUESTION_LENGTH + " caracteres");
        }
        IncidentSnapshot incident = requireIncident(user.organizationId(), incidentId);
        UUID answerId = messages.ask(user.organizationId(), incidentId, user.userId(), text)
                .orElseThrow(() -> new AssistantBusyException("O assistente ainda está respondendo a pergunta anterior"));
        try {
            executor.execute(() -> SystemActor.runAs(user.organizationId(), () -> answer(incident, answerId, text)));
        } catch (TaskRejectedException e) {
            messages.fail(user.organizationId(), answerId, "O assistente está ocupado agora. Tente de novo em instantes.");
        }
        return messages.list(user.organizationId(), incidentId, 100);
    }

    @Scheduled(fixedDelayString = "${argos.ai.watchdog-interval-ms:60000}", initialDelayString = "${argos.ai.watchdog-interval-ms:60000}")
    public void watchdog() {
        int failed = messages.failStale(properties.getAssistantTimeout());
        if (failed > 0) {
            log.warn("{} resposta(s) do assistente expiraram", failed);
        }
    }

    void answer(IncidentSnapshot incident, UUID answerId, String question) {
        UUID organizationId = incident.organizationId();
        if (!router.available()) {
            messages.answer(organizationId, answerId, "A IA não está configurada neste ambiente (falta a chave da API). "
                    + "A investigação automática continua funcionando em modo heurístico.", null, 0, 0, 0, null);
            return;
        }
        try (AgentContext context = AgentContext.open(organizationId, incident, clock.instant(), properties.getMaxToolCalls())) {
            seedKnownServices(context);
            LlmCallLog.start();
            try {
                Result<String> result = agent.answer(JdbcChatMemoryStore.conversationId(organizationId, incident.id()), question);
                List<LlmCall> calls = LlmCallLog.stop();
                String guardrail = context.guardrailEvents().isEmpty() ? null : context.guardrailEvents().get(0).type();
                messages.answer(organizationId, answerId, result.content(), lastModel(calls),
                        calls.stream().mapToInt(LlmCall::inputTokens).sum(), calls.stream().mapToInt(LlmCall::outputTokens).sum(),
                        calls.stream().mapToInt(LlmCall::cacheReadTokens).sum(), guardrail);
            } catch (InputGuardrailException e) {
                String reply = PromptInjectionDetector.detect(question).isPresent()
                        ? AssistantGuardrails.INJECTION_REFUSAL
                        : "Não consegui processar essa pergunta. Reformule e tente de novo.";
                messages.answer(organizationId, answerId, reply, null, 0, 0, 0, "BLOQUEADA");
            } catch (AllRoutesFailedException e) {
                log.warn("Assistente sem modelo disponível: {}", e.getMessage());
                messages.fail(organizationId, answerId, "Os modelos de IA estão indisponíveis agora. Tente de novo em instantes.");
            } catch (RuntimeException e) {
                log.error("Falha ao responder a pergunta {}", answerId, e);
                messages.fail(organizationId, answerId, "Não consegui responder agora. Tente de novo em instantes.");
            } finally {
                LlmCallLog.stop();
            }
        }
    }

    private void seedKnownServices(AgentContext context) {
        try {
            telemetry.topology(context.duringFrom(), context.duringTo()).nodes().stream()
                    .map(TopologyNode::service).forEach(context::addKnownService);
        } catch (RuntimeException e) {
            log.debug("Topologia indisponível para o assistente: {}", e.getMessage());
        }
    }

    private static String lastModel(List<LlmCall> calls) {
        return calls.stream().filter(call -> call.outcome() == LlmCallLog.Outcome.OK).reduce((a, b) -> b)
                .map(LlmCall::model).orElse(null);
    }

    private IncidentSnapshot requireIncident(UUID organizationId, UUID incidentId) {
        return incidents.find(organizationId, incidentId)
                .orElseThrow(() -> new AiNotFoundException("Incidente não encontrado"));
    }
}
