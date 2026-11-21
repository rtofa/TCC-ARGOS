package br.com.argos.argos_api.helpdesk.support;

import br.com.argos.argos_api.helpdesk.guardrail.PortugueseText;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for a real embedding model, so dense retrieval can be tested offline. Words are reduced to
 * stems, synonyms are mapped to one concept ("aviso", "notificação" -> notifica) and the concepts are hashed into a
 * normalized bag-of-concepts vector: texts that say the same thing with different words end up close.
 */
public class ConceptEmbeddingModel implements EmbeddingModel {

    private static final Map<String, String> CONCEPTS = Map.ofEntries(
            Map.entry("avis", "notifica"), Map.entry("notifi", "notifica"), Map.entry("comuni", "notifica"),
            Map.entry("publica", "deploy"), Map.entry("deploy", "deploy"), Map.entry("releas", "deploy"),
            Map.entry("lancam", "deploy"),
            Map.entry("credenc", "chave"), Map.entry("chave", "chave"), Map.entry("chaves", "chave"),
            Map.entry("token", "chave"),
            Map.entry("prejui", "impacto"), Map.entry("perd", "impacto"),
            Map.entry("impact", "impacto"), Map.entry("custo", "impacto"), Map.entry("dinhei", "impacto"),
            Map.entry("lentid", "latencia"), Map.entry("latenc", "latencia"), Map.entry("demora", "latencia"),
            Map.entry("lento", "latencia"),
            Map.entry("falha", "erro"), Map.entry("erro", "erro"), Map.entry("erros", "erro"), Map.entry("quebr", "erro"),
            Map.entry("membro", "usuario"), Map.entry("usuari", "usuario"), Map.entry("pessoa", "usuario"),
            Map.entry("colega", "usuario"),
            Map.entry("bloque", "bloqueio"), Map.entry("impedi", "bloqueio"), Map.entry("barrar", "bloqueio"),
            Map.entry("gravid", "severidade"), Map.entry("severi", "severidade"), Map.entry("critic", "severidade"));

    private final int dimensions;
    private final AtomicInteger calls = new AtomicInteger();

    public ConceptEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        calls.incrementAndGet();
        return Response.from(segments.stream().map(segment -> Embedding.from(vector(segment.text()))).toList());
    }

    @Override
    public Response<Embedding> embed(String text) {
        calls.incrementAndGet();
        return Response.from(Embedding.from(vector(text)));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return embed(segment.text());
    }

    @Override
    public int dimension() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return "conceitos-teste";
    }

    public int calls() {
        return calls.get();
    }

    float[] vector(String text) {
        float[] vector = new float[dimensions];
        for (String stem : PortugueseText.contentStems(text)) {
            String concept = concept(stem);
            vector[Math.floorMod(concept.hashCode(), dimensions)] += 1f;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            vector[0] = 1f;
            return vector;
        }
        float length = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
    }

    private static String concept(String stem) {
        for (Map.Entry<String, String> entry : CONCEPTS.entrySet()) {
            if (stem.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return stem;
    }
}
