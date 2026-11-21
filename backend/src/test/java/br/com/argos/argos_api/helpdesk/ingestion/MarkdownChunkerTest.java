package br.com.argos.argos_api.helpdesk.ingestion;

import br.com.argos.argos_api.helpdesk.ingestion.MarkdownChunker.SourceFile;
import br.com.argos.argos_api.helpdesk.model.ChunkedDocument;
import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.ParentSection;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private static final String DOC = """
            ---
            title: Alertas e monitoramento
            ---

            # Alertas

            Introdução sobre alertas.

            ## Monitor SLO OTLP

            O monitor avalia a telemetria de cada serviço.

            ### Taxa de erro

            Abre um incidente quando a taxa de erro passa do limite.

            ### Latência p95

            Abre um incidente quando a latência p95 passa do limite.

            ## Webhook

            Exemplo de corpo:

            ```json
            # isto não é um título
            {"title": "CPU alta", "status": "firing"}
            ```
            """;

    private final MarkdownChunker chunker = new MarkdownChunker(2, 3000, 600, 900);

    @Test
    void parentsFollowTheSecondLevelSectionsAndKeepTheirSubsections() {
        ChunkedDocument document = chunker.chunk(new SourceFile("guias/Alertas e Monitoramento.md", DOC));

        assertThat(document.docId()).isEqualTo("guias-alertas-e-monitoramento");
        assertThat(document.title()).as("front matter wins over the H1").isEqualTo("Alertas e monitoramento");
        assertThat(document.parents()).extracting(ParentSection::sectionPath)
                .containsExactly("", "Monitor SLO OTLP", "Webhook");
        ParentSection monitor = document.parents().get(1);
        assertThat(monitor.content()).contains("## Monitor SLO OTLP", "### Taxa de erro", "### Latência p95");
    }

    @Test
    void childrenCarryTheirHeadingPathAndTheDocumentTitle() {
        ChunkedDocument document = chunker.chunk(new SourceFile("alertas.md", DOC));

        KnowledgeChunk latency = document.chunks().stream()
                .filter(chunk -> chunk.body().contains("latência p95 passa")).findFirst().orElseThrow();
        assertThat(latency.sectionPath()).isEqualTo("Monitor SLO OTLP > Latência p95");
        assertThat(latency.contextualText()).startsWith("""
                Documento: Alertas e monitoramento
                Seção: Monitor SLO OTLP > Latência p95

                Abre um incidente""");
        Set<String> parentIds = document.parents().stream().map(ParentSection::parentId).collect(Collectors.toSet());
        assertThat(document.chunks()).allSatisfy(chunk -> assertThat(parentIds).contains(chunk.parentId()));
    }

    @Test
    void codeFencesAreNeverReadAsHeadingsNorCut() {
        ChunkedDocument document = chunker.chunk(new SourceFile("alertas.md", DOC));

        assertThat(document.parents()).extracting(ParentSection::sectionPath).doesNotContain("isto não é um título");
        KnowledgeChunk code = document.chunks().stream().filter(chunk -> chunk.body().contains("```json")).findFirst()
                .orElseThrow();
        assertThat(code.body()).contains("# isto não é um título").endsWith("```");
    }

    @Test
    void longSectionsBecomeSeveralChildrenWithinTheSizeLimits() {
        String paragraph = "O Argos registra cada deploy enviado pelo pipeline com versão, autor e commit. ".repeat(6);
        StringBuilder body = new StringBuilder("# Deploys\n\n## Histórico\n\n");
        for (int i = 0; i < 8; i++) {
            body.append(paragraph).append("\n\n");
        }
        ChunkedDocument document = chunker.chunk(new SourceFile("deploys.md", body.toString()));

        assertThat(document.chunks()).hasSizeGreaterThan(4);
        assertThat(document.chunks()).allSatisfy(chunk -> assertThat(chunk.body().length()).isLessThanOrEqualTo(900));
        assertThat(document.chunks()).allSatisfy(chunk -> assertThat(chunk.sectionPath()).isEqualTo("Histórico"));
    }

    @Test
    void oversizedParentsAreSplitAtSubsectionBoundaries() {
        MarkdownChunker small = new MarkdownChunker(2, 500, 200, 300);
        String text = "Cada canal tem nome, tipo, destino e severidade mínima para os avisos. ".repeat(4);
        String doc = "# Integrações\n\n## Canais\n\n### Slack\n\n" + text + "\n\n### Discord\n\n" + text
                + "\n\n### E-mail\n\n" + text;

        ChunkedDocument document = small.chunk(new SourceFile("integracoes.md", doc));

        assertThat(document.parents()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(document.parents()).allSatisfy(parent -> assertThat(parent.content().length()).isLessThanOrEqualTo(500));
        assertThat(document.parents()).extracting(ParentSection::sectionPath)
                .contains("Canais > Discord", "Canais > E-mail");
    }

    @Test
    void documentsWithoutHeadingsOrFrontMatterStillGetATitle() {
        ChunkedDocument document = chunker.chunk(new SourceFile("pasta/perguntas_frequentes.md", "Texto solto sem títulos."));

        assertThat(document.title()).isEqualTo("Perguntas frequentes");
        assertThat(document.chunks()).hasSize(1);
        assertThat(document.chunks().get(0).contextualText()).isEqualTo("Documento: Perguntas frequentes\n\nTexto solto sem títulos.");
    }
}
