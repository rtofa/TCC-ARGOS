package br.com.argos.argos_api.helpdesk;

import br.com.argos.argos_api.TestcontainersConfiguration;
import br.com.argos.argos_api.ai.llm.LlmRouter;
import br.com.argos.argos_api.helpdesk.ingestion.KnowledgeBaseIngestor;
import br.com.argos.argos_api.helpdesk.model.Outcome;
import br.com.argos.argos_api.helpdesk.model.SourceReference;
import br.com.argos.argos_api.helpdesk.model.SupportAnswer;
import br.com.argos.argos_api.helpdesk.model.SupportRequest;
import br.com.argos.argos_api.helpdesk.support.GoldenQuestions;
import br.com.argos.argos_api.helpdesk.support.GoldenQuestions.Golden;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answer-quality evaluation with the real models (opt-in: {@code ARGOS_LIVE_EVAL=true}, keys in backend/.env).
 *
 * <p>Runs every reference question through the whole agent and measures: how many are answered, whether the answer
 * cites the expected document, whether an independent judge (the same LLM router, separate prompt) finds the answer
 * fully supported by the cited passages, and that off-topic questions are never answered. Writes a report to
 * {@code target/helpdesk-live-eval.md}.
 */
@SpringBootTest(properties = "argos.helpdesk.knowledge-base.ingest-on-startup=false")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ARGOS_LIVE_EVAL", matches = "true")
class HelpdeskLiveEvaluationIT {

    private static final List<String> OFF_TOPIC = List.of(
            "Qual é a capital da França?",
            "Escreva um poema sobre o mar.",
            "Como faço um bolo de cenoura?",
            "Quem ganhou a Copa do Mundo de 2002?");

    @Autowired
    private KnowledgeBaseIngestor ingestor;
    @Autowired
    private SupportService service;
    @Autowired
    private LlmRouter router;

    @Test
    void answersAreGroundedCiteTheRightDocumentAndOffTopicIsRefused() throws Exception {
        assertThat(router.available()).as("configure ARGOS_LLM_PRIMARY_* em backend/.env").isTrue();
        ingestor.ingest(false);

        List<String> rows = new ArrayList<>();
        int answered = 0;
        int citedExpected = 0;
        int faithful = 0;
        long totalMs = 0;
        List<Golden> golden = GoldenQuestions.load();
        for (Golden question : golden) {
            SupportAnswer answer = service.ask(new SupportRequest(question.question(), null)).get(3, TimeUnit.MINUTES);
            totalMs += answer.trace().totalMs();
            boolean cites = answer.sources().stream()
                    .anyMatch(s -> question.documents().stream().anyMatch(doc -> s.source().startsWith(doc)));
            boolean supported = false;
            if (answer.outcome() == Outcome.ANSWERED) {
                answered++;
                citedExpected += cites ? 1 : 0;
                supported = judge(question.question(), answer);
                faithful += supported ? 1 : 0;
            }
            rows.add("| %s | %s | %s | %s | %d ms |".formatted(question.question(), answer.outcome(), cites ? "sim" : "não",
                    answer.outcome() == Outcome.ANSWERED ? (supported ? "sim" : "não") : "-", answer.trace().totalMs()));
        }
        int offTopicAnswered = 0;
        for (String question : OFF_TOPIC) {
            SupportAnswer answer = service.ask(new SupportRequest(question, null)).get(3, TimeUnit.MINUTES);
            offTopicAnswered += answer.outcome() == Outcome.ANSWERED ? 1 : 0;
            rows.add("| %s (fora do escopo) | %s | - | - | %d ms |".formatted(question, answer.outcome(), answer.trace().totalMs()));
        }

        double answeredRate = (double) answered / golden.size();
        double citationRate = answered == 0 ? 0 : (double) citedExpected / answered;
        double faithfulness = answered == 0 ? 0 : (double) faithful / answered;
        String report = """
                # Avaliação do agente de suporte (modelos reais)

                - Respondidas: %d de %d (%.0f%%)
                - Citaram o documento esperado: %.0f%% das respondidas
                - Fidelidade (juiz LLM): %.0f%% das respondidas
                - Fora do escopo respondidas: %d de %d
                - Latência média: %d ms

                | Pergunta | Resultado | Cita o documento esperado | Sustentada pelos trechos | Tempo |
                |---|---|---|---|---|
                %s
                """.formatted(answered, golden.size(), answeredRate * 100, citationRate * 100, faithfulness * 100,
                offTopicAnswered, OFF_TOPIC.size(), totalMs / golden.size(), String.join("\n", rows));
        Files.writeString(Path.of("target", "helpdesk-live-eval.md"), report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(offTopicAnswered).as("perguntas fora do escopo respondidas").isZero();
        assertThat(answeredRate).as("taxa de resposta").isGreaterThanOrEqualTo(0.7);
        assertThat(citationRate).as("citação do documento esperado").isGreaterThanOrEqualTo(0.8);
        assertThat(faithfulness).as("fidelidade aos trechos").isGreaterThanOrEqualTo(0.9);
    }

    /** LLM-as-judge: is every statement of the answer supported by the passages it cites? */
    private boolean judge(String question, SupportAnswer answer) {
        StringBuilder sources = new StringBuilder();
        for (SourceReference source : answer.sources()) {
            sources.append("[").append(source.index()).append("] ").append(source.document()).append(" > ")
                    .append(source.section()).append(": ").append(source.snippet()).append('\n');
        }
        String prompt = """
                Você avalia respostas de um assistente de suporte. Considere apenas os trechos abaixo como verdade.

                Pergunta: %s

                Trechos citados (resumidos):
                %s
                Resposta:
                %s

                Toda afirmação da resposta é compatível com os trechos e responde à pergunta, sem inventar dados?
                Responda apenas SIM ou NAO.""".formatted(question, sources, answer.answer());
        String verdict = router.chat(ChatRequest.builder().messages(UserMessage.from(prompt)).build()).aiMessage().text();
        return verdict != null && verdict.strip().toUpperCase(Locale.ROOT).startsWith("SIM");
    }
}
