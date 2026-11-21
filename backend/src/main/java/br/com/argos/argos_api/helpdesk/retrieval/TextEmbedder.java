package br.com.argos.argos_api.helpdesk.retrieval;

import br.com.argos.argos_api.ai.AiProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dense representation of questions and chunks (OpenAI {@code text-embedding-3-small} by default, through
 * LangChain4j's {@link EmbeddingModel}). Without an embedding key, semantic retrieval is off and the agent runs on
 * full-text search alone.
 */
@Component
public class TextEmbedder {

    private final Optional<EmbeddingModel> model;
    private final String modelName;
    private final int dimensions;
    private final int batchSize;

    @Autowired
    public TextEmbedder(ObjectProvider<EmbeddingModel> model, AiProperties aiProperties) {
        this(Optional.ofNullable(model.getIfAvailable()), aiProperties.getEmbedding().getModel(),
                aiProperties.getEmbedding().getDimensions(), aiProperties.getEmbedding().getBatchSize());
    }

    public TextEmbedder(Optional<EmbeddingModel> model, String modelName, int dimensions, int batchSize) {
        this.model = model;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, batchSize);
    }

    public boolean available() {
        return model.isPresent();
    }

    /** Identifies the vector space: changing the model means re-embedding every chunk. */
    public String fingerprint() {
        return model.isPresent() ? modelName + "@" + dimensions : "none";
    }

    public String modelName() {
        return model.isPresent() ? modelName : null;
    }

    /** Vector size of the index; also used to create it while embeddings are off. */
    public int dimensions() {
        return dimensions;
    }

    /** One vector per text, in order, sent in batches. */
    public List<float[]> embed(List<String> texts) {
        EmbeddingModel embeddingModel = model.orElseThrow(() -> new IllegalStateException("Embeddings não configurados"));
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<TextSegment> batch = texts.subList(start, Math.min(texts.size(), start + batchSize)).stream()
                    .map(TextSegment::from).toList();
            List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
            if (embeddings == null || embeddings.size() != batch.size()) {
                throw new IllegalStateException("O modelo de embeddings devolveu " + (embeddings == null ? 0 : embeddings.size())
                        + " vetores para " + batch.size() + " textos");
            }
            for (Embedding embedding : embeddings) {
                float[] vector = embedding.vector();
                if (vector.length != dimensions) {
                    throw new IllegalStateException("Vetor com " + vector.length + " dimensões; o índice espera " + dimensions
                            + " (ajuste argos.ai.embedding.dimensions)");
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }
}
