package br.com.argos.argos_api.helpdesk.prompt;

import br.com.argos.argos_api.ai.guardrail.PromptInjectionDetector;
import br.com.argos.argos_api.helpdesk.model.ChatTurn;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import dev.langchain4j.model.input.PromptTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The agent's prompts, versioned as resource files ({@code helpdesk/prompts}) instead of strings in code, and
 * rendered with LangChain4j {@link PromptTemplate}s. Loaded once at startup: a missing prompt fails the boot.
 *
 * <p>Context engineering: documents go inside {@code <documentos>} as numbered {@code <documento>} elements with
 * their title and section; their text is neutralized so it cannot close the wrapper and pose as instructions.
 */
public class PromptLibrary {

    private static final String BASE = "helpdesk/prompts/";

    private final String system;
    private final PromptTemplate user;
    private final String queryAnalysis;
    private final Properties responses = new Properties();

    public PromptLibrary() {
        this.system = read("system.txt");
        this.user = PromptTemplate.from(read("user.txt"));
        this.queryAnalysis = read("query-analysis.txt");
        try (InputStream stream = resource("responses.properties")) {
            responses.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String key : List.of("greeting", "out-of-scope", "blocked", "degraded")) {
            if (responses.getProperty(key, "").isBlank()) {
                throw new IllegalStateException("Resposta fixa ausente em " + BASE + "responses.properties: " + key);
            }
        }
    }

    public String system() {
        return system;
    }

    public String queryAnalysis() {
        return queryAnalysis;
    }

    public String user(List<RetrievedPassage> passages, String question) {
        return user.apply(Map.of("documents", documents(passages), "question", question)).text();
    }

    /** Passages as numbered, labelled elements; the model cites them as [n]. */
    public static String documents(List<RetrievedPassage> passages) {
        StringBuilder text = new StringBuilder();
        for (RetrievedPassage passage : passages) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append("<documento indice=\"").append(passage.index())
                    .append("\" titulo=\"").append(attribute(passage.section().docTitle()))
                    .append("\" secao=\"").append(attribute(passage.section().sectionPath()))
                    .append("\">\n")
                    .append(PromptInjectionDetector.neutralize(passage.content()).replace("</documento", "‹/documento"))
                    .append("\n</documento>");
        }
        return text.toString();
    }

    /** History and question for the query-understanding step. */
    public String analysisInput(String question, List<ChatTurn> history) {
        StringBuilder text = new StringBuilder();
        if (!history.isEmpty()) {
            text.append("<historico>\n");
            for (ChatTurn turn : history) {
                text.append(turn.role() == ChatTurn.Role.USER ? "Usuário: " : "Assistente: ")
                        .append(PromptInjectionDetector.neutralize(turn.content())).append('\n');
            }
            text.append("</historico>\n\n");
        }
        return text.append("<pergunta>\n").append(PromptInjectionDetector.neutralize(question)).append("\n</pergunta>")
                .toString();
    }

    public String greeting() {
        return responses.getProperty("greeting");
    }

    public String outOfScope() {
        return responses.getProperty("out-of-scope");
    }

    public String blocked() {
        return responses.getProperty("blocked");
    }

    public String degraded() {
        return responses.getProperty("degraded");
    }

    private static String attribute(String value) {
        return value == null ? "" : value.replace("\"", "'").replace("<", "‹").replace(">", "›");
    }

    private static String read(String name) {
        try (InputStream stream = resource(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String name) {
        InputStream stream = PromptLibrary.class.getClassLoader().getResourceAsStream(BASE + name);
        if (stream == null) {
            throw new IllegalStateException("Prompt não encontrado no classpath: " + BASE + name);
        }
        return stream;
    }
}
