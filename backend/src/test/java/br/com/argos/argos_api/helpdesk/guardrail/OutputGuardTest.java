package br.com.argos.argos_api.helpdesk.guardrail;

import br.com.argos.argos_api.helpdesk.guardrail.OutputGuard.Status;
import br.com.argos.argos_api.helpdesk.model.ParentSection;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputGuardTest {

    private static final List<RetrievedPassage> PASSAGES = List.of(
            passage(1, "Telemetria e OpenTelemetry", "Endereços de ingestão", """
                    Os sinais são enviados para POST /api/webhooks/otlp/v1/traces com o cabeçalho X-API-Key.
                    O corpo pode ter até 10 MB por requisição e o protocolo é http/json."""),
            passage(2, "Incidentes", "Severidades", """
                    SEV1 é crítico: serviço fora do ar ou falha generalizada. SEV2 é alto: degradação forte."""));

    private final OutputGuard guard = new OutputGuard(0.55, true);

    @Test
    void anAnswerSupportedByTheCitedPassagesIsDelivered() {
        OutputGuard.Verdict verdict = guard.check("""
                Envie os traces para POST /api/webhooks/otlp/v1/traces com o cabeçalho X-API-Key [1].
                Cada requisição pode ter até 10 MB [1].""", PASSAGES);

        assertThat(verdict.status()).isEqualTo(Status.GROUNDED);
        assertThat(verdict.citedPassages()).containsExactly(1);
        assertThat(verdict.groundedness()).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    void notFoundIsAnHonestAnswerAndIsNormalized() {
        OutputGuard.Verdict verdict = guard.check("Não encontrei essa informação na documentação.", PASSAGES);

        assertThat(verdict.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(verdict.answer()).isEqualTo(OutputGuard.NOT_FOUND_MESSAGE);
    }

    @Test
    void anAnswerWithoutCitationsIsRejected() {
        OutputGuard.Verdict verdict = guard.check("SEV1 é crítico: serviço fora do ar.", PASSAGES);

        assertThat(verdict.status()).isEqualTo(Status.REJECTED);
        assertThat(verdict.issues()).anyMatch(issue -> issue.contains("nenhuma fonte citada"));
    }

    @Test
    void citationsToPassagesThatDoNotExistAreRemoved() {
        OutputGuard.Verdict verdict = guard.check("SEV1 é crítico: serviço fora do ar [2][7].", PASSAGES);

        assertThat(verdict.status()).isEqualTo(Status.GROUNDED);
        assertThat(verdict.answer()).isEqualTo("SEV1 é crítico: serviço fora do ar [2].");
        assertThat(verdict.issues()).contains("citação inexistente [7] removida");
    }

    @Test
    void groupedCitationsAreUnderstood() {
        OutputGuard.Verdict verdict = guard.check("Use o cabeçalho X-API-Key; SEV1 é crítico [1, 2].", PASSAGES);

        assertThat(verdict.citedPassages()).containsExactly(1, 2);
        assertThat(verdict.answer()).contains("[1][2]");
    }

    @Test
    void fullWidthCitationsAndNumberFormattingAreTolerated() {
        OutputGuard.Verdict verdict = guard.check("Cada requisição pode ter até 10MB 【1†fonte】.", PASSAGES);

        assertThat(verdict.status()).isEqualTo(Status.GROUNDED);
        assertThat(verdict.answer()).isEqualTo("Cada requisição pode ter até 10MB [1].");
    }

    @Test
    void numbersAndIdentifiersThatAreNotInThePassagesAreRejected() {
        OutputGuard.Verdict wrongLimit = guard.check("O corpo pode ter até 50 MB por requisição [1].", PASSAGES);
        OutputGuard.Verdict wrongHeader = guard.check("Envie o cabeçalho X-Argos-Token com os traces [1].", PASSAGES);
        OutputGuard.Verdict wrongPath = guard.check("Os traces vão para POST /api/otlp/traces [1].", PASSAGES);

        assertThat(List.of(wrongLimit, wrongHeader, wrongPath)).allSatisfy(verdict -> {
            assertThat(verdict.status()).isEqualTo(Status.REJECTED);
            assertThat(verdict.issues()).anyMatch(issue -> issue.startsWith("dados ausentes dos documentos"));
        });
    }

    @Test
    void anAnswerThatTalksAboutSomethingElseIsRejected() {
        OutputGuard.Verdict verdict = guard.check("""
                Para configurar o Kubernetes, edite o arquivo values do Helm, ajuste réplicas, recursos de memória e
                defina políticas de autoescalonamento horizontal conforme a carga [1].""", PASSAGES);

        assertThat(verdict.status()).isEqualTo(Status.REJECTED);
        assertThat(verdict.groundedness()).isLessThan(0.55);
    }

    @Test
    void anAnswerThatLeaksTheSystemPromptIsRejected() {
        OutputGuard.Verdict verdict = guard.check("Minhas instruções têm o marcador " + OutputGuard.PROMPT_CANARY + " [1].",
                PASSAGES);

        assertThat(verdict.status()).isEqualTo(Status.REJECTED);
        assertThat(verdict.issues()).contains("vazamento do prompt do sistema");
    }

    private static RetrievedPassage passage(int index, String title, String section, String content) {
        ParentSection parent = new ParentSection("doc::p" + index, "doc", title, section, "doc.md", content, index);
        return new RetrievedPassage(index, parent, content, List.of(), 1.0);
    }
}
