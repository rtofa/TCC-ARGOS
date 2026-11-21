package br.com.argos.argos_api.helpdesk.retrieval;

import br.com.argos.argos_api.ai.AiProperties;
import br.com.argos.argos_api.helpdesk.retrieval.ReciprocalRankFusion.Candidate;
import org.springframework.beans.factory.annotation.Autowired;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Second-stage ranking with a cross-encoder (Cohere Rerank through LangChain4j's {@link ScoringModel}): it reads the
 * question and each candidate together, which separates "mentions the same words" from "actually answers it".
 */
@Component
public class Reranker {

    private final Optional<ScoringModel> model;
    private final String modelName;

    @Autowired
    public Reranker(ObjectProvider<ScoringModel> model, AiProperties aiProperties) {
        this(Optional.ofNullable(model.getIfAvailable()), aiProperties.getRerank().getModel());
    }

    public Reranker(Optional<ScoringModel> model, String modelName) {
        this.model = model;
        this.modelName = model.isPresent() ? modelName : null;
    }

    public boolean available() {
        return model.isPresent();
    }

    public String modelName() {
        return modelName;
    }

    /** Candidates with their rerank score, best first. Fails when the reranker fails (callers degrade). */
    public List<Candidate> rerank(String question, List<Candidate> candidates) {
        ScoringModel scoring = model.orElseThrow(() -> new IllegalStateException("Reranker não configurado"));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = candidates.stream().map(c -> TextSegment.from(c.chunk().contextualText())).toList();
        Response<List<Double>> response = scoring.scoreAll(segments, question);
        List<Double> scores = response.content();
        if (scores == null || scores.size() != candidates.size()) {
            throw new IllegalStateException("O reranker devolveu " + (scores == null ? 0 : scores.size())
                    + " notas para " + candidates.size() + " trechos");
        }
        List<Candidate> reranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            reranked.add(candidates.get(i).withRerankScore(scores.get(i)));
        }
        reranked.sort(Comparator.comparingDouble(Candidate::relevance).reversed());
        return reranked;
    }
}
