package br.com.argos.argos_api.ai.agent;

import br.com.argos.argos_api.ai.AiProperties;
import br.com.argos.argos_api.ai.llm.CircuitBreaker;
import br.com.argos.argos_api.ai.llm.LlmRoute;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.ai.memory.IncidentMemory;
import br.com.argos.argos_api.alerting.IncidentAlerts;
import br.com.argos.argos_api.business.BusinessImpactService;
import br.com.argos.argos_api.business.BusinessImpactService.IncidentImpact;
import br.com.argos.argos_api.alerting.IncidentAlerts.LinkedAlert;
import br.com.argos.argos_api.delivery.DeployHistory;
import br.com.argos.argos_api.delivery.DeployHistory.DeployEvent;
import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.telemetry.TelemetryQueryService;
import br.com.argos.argos_api.telemetry.TelemetryViews.RedSeries;
import br.com.argos.argos_api.telemetry.TelemetryViews.ServiceTopology;
import br.com.argos.argos_api.telemetry.TelemetryViews.TopologyNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the real LangChain4j agent (tools, memory, output guardrail, routing) against a scripted model, so the
 * agent loop is verified end to end without calling a paid API.
 */
class InvestigatorAgentTest {

    private static final Instant OPENED = Instant.parse("2026-11-14T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final IncidentSnapshot INCIDENT = new IncidentSnapshot(UUID.randomUUID(), ORG, "Checkout com erro 500",
            "Clientes não conseguem pagar", "SEV1", "OPEN", "ALERT", List.of("checkout"), OPENED, null, null);

    private final TelemetryQueryService telemetry = mock(TelemetryQueryService.class);
    private final DeployHistory deploys = mock(DeployHistory.class);
    private final IncidentMemory memory = mock(IncidentMemory.class);
    private final IncidentQueries incidents = mock(IncidentQueries.class);
    private final IncidentAlerts alerts = mock(IncidentAlerts.class);
    private final BusinessImpactService business = mock(BusinessImpactService.class);
    private final List<List<ChatMessage>> requests = new ArrayList<>();
    private final ReportValidator validator = new ReportValidator(JsonMapper.builder().build());
    private final Clock clock = Clock.fixed(OPENED.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);

    @BeforeEach
    void data() {
        RedSeries quiet = new RedSeries(OPENED, OPENED, "checkout", 60, 600, 1, 10, 0.2, 40, 90, 120, List.of());
        RedSeries failing = new RedSeries(OPENED, OPENED, "checkout", 60, 600, 240, 10, 40, 60, 900, 1500, List.of());
        when(telemetry.red(eq("checkout"), any(), any())).thenReturn(quiet, failing);
        when(telemetry.topology(any(), any())).thenReturn(new ServiceTopology(OPENED, OPENED,
                List.of(new TopologyNode("checkout", 600, 240, 40, 300, 900, true)), List.of()));
        when(incidents.timeline(ORG, INCIDENT.id())).thenReturn(List.of());
        when(alerts.forIncident(ORG, INCIDENT.id())).thenReturn(List.of(new LinkedAlert("Grafana", "checkout-error-rate", 3,
                OPENED, OPENED, null)));
        when(memory.similar(any(), any(), anyString(), anyInt())).thenReturn(List.of());
        when(business.estimate(any(), any())).thenReturn(new IncidentImpact(INCIDENT.id(), true, 5, "SEVERIDADE",
                List.of(), BigDecimal.ZERO, "BRL"));
        when(deploys.between(eq(ORG), any(), any(), any(), anyInt())).thenReturn(List.of(new DeployEvent("checkout", "v2.4.1",
                "production", "9fceb02d0ae5", "joao.dev@loja.com", "troca do cliente HTTP do gateway", null, null,
                OPENED.minus(Duration.ofMinutes(12)))));
    }

    @Test
    void investigatesWithToolsAndTheGuardrailSendsUngroundedAnswersBack() {
        ChatModel scripted = scripted(request -> switch (requests.size()) {
            case 1 -> toolCall("deploys_recentes", "{\"servico\":\"checkout\",\"horasAntes\":6}");
            case 2 -> text(report("v9.9.9")); // cites a version that never appeared: must be rejected
            default -> text(report("v2.4.1"));
        });

        InvestigationOutcome outcome = investigator(new LlmRouter("teste", List.of(route("primario", scripted))))
                .investigate(UUID.randomUUID(), INCIDENT);

        assertThat(outcome.mode()).isEqualTo("AI");
        assertThat(outcome.report().rootCause().confidence()).isEqualTo("ALTA");
        assertThat(outcome.report().evidence()).extracting(InvestigationReport.Evidence::reference).contains("v2.4.1");
        assertThat(outcome.steps()).extracting(AgentContext.Step::tool).containsExactly("deploys_recentes");
        assertThat(outcome.guardrailEvents()).extracting(AgentContext.GuardrailEvent::type)
                .contains("RELATORIO_CORRIGIDO", "PII_MASCARADA");
        assertThat(requests).hasSize(3);

        ToolExecutionResultMessage toolResult = requests.get(1).stream()
                .filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElseThrow();
        assertThat(toolResult.text()).startsWith("<dados fonte=\"deploys_recentes\"").contains("[email]")
                .doesNotContain("joao.dev@loja.com");
        assertThat(requests.get(2).get(requests.get(2).size() - 1).toString()).contains("v9.9.9");
        assertThat(outcome.inputTokens()).isEqualTo(300);
    }

    @Test
    void fallsBackToTheNextModelInTheMiddleOfTheToolLoop() {
        ChatModel primary = scripted(request -> requests.size() == 1
                ? toolCall("saude_do_servico", "{\"servico\":\"checkout\"}")
                : failWith(new InternalServerException("overloaded")));
        ChatModel fallback = scripted(request -> text(report("v2.4.1")));

        InvestigationOutcome outcome = investigator(new LlmRouter("teste",
                List.of(route("primario", primary), route("reserva", fallback)))).investigate(UUID.randomUUID(), INCIDENT);

        assertThat(outcome.mode()).isEqualTo("AI");
        assertThat(outcome.fallbackUsed()).isTrue();
        assertThat(outcome.model()).isEqualTo("modelo-reserva");
    }

    @Test
    void withoutAnyModelTheHeuristicStillDeliversAFirstAnalysis() {
        InvestigationOutcome outcome = investigator(new LlmRouter("teste", List.of())).investigate(UUID.randomUUID(), INCIDENT);

        assertThat(outcome.mode()).isEqualTo("HEURISTIC");
        assertThat(outcome.report().rootCause().summary()).contains("v2.4.1");
        assertThat(outcome.report().rootCause().confidence()).isEqualTo("MEDIA");
        assertThat(outcome.report().alertExplanations()).extracting(InvestigationReport.AlertExplanation::alert)
                .containsExactly("checkout-error-rate");
    }

    private IncidentInvestigator investigator(LlmRouter router) {
        InvestigationTools tools = new InvestigationTools(telemetry, deploys, memory, business);
        InvestigatorAgent agent = AgentFactory.investigator(router, tools, validator, new InMemoryChatMemoryStore(),
                "Você investiga incidentes.", 12, 2);
        AiProperties properties = new AiProperties();
        return new IncidentInvestigator(agent, router, new BriefingBuilder(incidents, alerts, deploys, telemetry, memory, business),
                new HeuristicInvestigator(), validator, properties, clock);
    }

    private ChatModel scripted(Function<ChatRequest, ChatResponse> behaviour) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requests.add(List.copyOf(request.messages()));
                return behaviour.apply(request);
            }
        };
    }

    private static ChatResponse toolCall(String tool, String arguments) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call-1").name(tool).arguments(arguments).build())))
                .finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(100, 10))
                .build();
    }

    private static ChatResponse text(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(100, 50)).build();
    }

    private static ChatResponse failWith(RuntimeException error) {
        throw error;
    }

    private static String report(String version) {
        return """
                {"rootCause":{"summary":"O deploy %1$s do checkout trocou o cliente HTTP do gateway e as chamadas de pagamento passaram a falhar.","confidence":"ALTA","service":"checkout"},
                 "hypotheses":[{"description":"Regressão do deploy %1$s","status":"CONFIRMADA"}],
                 "evidence":[{"kind":"DEPLOY","description":"Deploy 12 min antes da abertura","reference":"%1$s"},
                             {"kind":"METRICA","description":"Erros de 0,2%% para 40%%","reference":"checkout"}],
                 "alertExplanations":[{"alert":"checkout-error-rate","explanation":"Taxa de erro do checkout acima do limite","whatToCheck":"Logs do cliente HTTP"}],
                 "technicalSummary":"Após o deploy %1$s, 40%% das requisições do checkout falham.",
                 "executiveSummary":"Clientes não conseguem concluir compras desde 11:48.",
                 "nextSteps":["Fazer rollback do checkout para a versão anterior"]}
                """.formatted(version);
    }

    private static LlmRoute route(String name, ChatModel model) {
        return new LlmRoute(name, "modelo-" + name, model, new CircuitBreaker(3, Duration.ofMinutes(1), Clock.systemUTC()),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
    }
}
