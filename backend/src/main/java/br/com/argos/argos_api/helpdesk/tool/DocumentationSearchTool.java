package br.com.argos.argos_api.helpdesk.tool;

import br.com.argos.argos_api.helpdesk.agent.AgentListener;
import br.com.argos.argos_api.helpdesk.agent.PassageRegistry;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import br.com.argos.argos_api.helpdesk.observability.ExecutionTrace;
import br.com.argos.argos_api.helpdesk.prompt.PromptLibrary;
import br.com.argos.argos_api.helpdesk.retrieval.HybridRetriever;
import br.com.argos.argos_api.ai.guardrail.PromptInjectionDetector;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * Tool the model can call while answering (LangChain4j tool calling) when the initial passages are not enough,
 * e.g. a question that spans two features. It runs the same hybrid retrieval; new passages join the registry, so
 * they can be cited and are checked by the output guardrail like the others.
 *
 * <p>One instance per question: it carries that question's passages and trace.
 */
public class DocumentationSearchTool {

    public static final String NAME = "buscar_documentacao";
    private static final int MAX_QUERY_CHARS = 200;

    private final HybridRetriever retriever;
    private final PassageRegistry registry;
    private final ExecutionTrace trace;
    private final AgentListener listener;
    private int calls;

    public DocumentationSearchTool(HybridRetriever retriever, PassageRegistry registry, ExecutionTrace trace,
                                   AgentListener listener) {
        this.retriever = retriever;
        this.registry = registry;
        this.trace = trace;
        this.listener = listener;
    }

    @Tool(name = NAME, value = "Busca trechos adicionais na documentação oficial do Argos. Use somente quando os "
            + "documentos fornecidos não bastarem para responder. Devolve trechos numerados que podem ser citados.")
    public String buscarDocumentacao(@P("consulta curta e específica, em português, com os termos que devem aparecer "
            + "na documentação") String consulta) {
        calls++;
        String query = consulta == null ? "" : consulta.strip();
        if (query.isEmpty()) {
            return "Informe uma consulta.";
        }
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS);
        }
        if (PromptInjectionDetector.detect(query).isPresent()) {
            return "Consulta recusada.";
        }
        listener.onStage("searching");
        HybridRetriever.Result result = retriever.retrieve(query, List.of(query), trace, registry.nextIndex());
        List<RetrievedPassage> added = registry.add(result.passages());
        listener.onSources(registry.references());
        if (added.isEmpty()) {
            return "Nenhum trecho novo encontrado para essa consulta.";
        }
        return PromptLibrary.documents(added);
    }

    public int calls() {
        return calls;
    }
}
