package br.com.argos.argos_api.helpdesk.retrieval;

import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.ScoredChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion across the searches of one question (the question itself and its reformulations):
 * {@code score(chunk) = Σ 1 / (k + rank)} over every result list that contains it, the same formula the hybrid
 * search uses inside each query to fuse its full-text and vector rankings. A chunk found by several queries rises.
 */
public final class ReciprocalRankFusion {

    /**
     * @param similarity  best cosine similarity to any of the queries, or null without embeddings
     * @param rerankScore set later by the reranker
     */
    public record Candidate(KnowledgeChunk chunk, double fusedScore, Double similarity, int bestRank,
                            Double rerankScore) {

        public Candidate withRerankScore(double score) {
            return new Candidate(chunk, fusedScore, similarity, bestRank, score);
        }

        /** Score that orders the final list: the reranker's when it ran, the fused score otherwise. */
        public double relevance() {
            return rerankScore != null ? rerankScore : fusedScore;
        }
    }

    private ReciprocalRankFusion() {
    }

    public static List<Candidate> fuse(List<List<ScoredChunk>> rankings, int k) {
        Map<String, Accumulator> byChunk = new LinkedHashMap<>();
        for (List<ScoredChunk> hits : rankings) {
            for (int position = 0; position < hits.size(); position++) {
                ScoredChunk hit = hits.get(position);
                int rank = position + 1;
                Accumulator accumulator = byChunk.computeIfAbsent(hit.chunk().chunkId(), id -> new Accumulator(hit.chunk()));
                accumulator.fused += 1.0 / (k + rank);
                accumulator.bestRank = Math.min(accumulator.bestRank, rank);
                if (hit.similarity() != null) {
                    accumulator.similarity = accumulator.similarity == null
                            ? hit.similarity() : Math.max(accumulator.similarity, hit.similarity());
                }
            }
        }
        List<Candidate> fused = new ArrayList<>();
        for (Accumulator a : byChunk.values()) {
            fused.add(new Candidate(a.chunk, a.fused, a.similarity, a.bestRank, null));
        }
        fused.sort(Comparator.comparingDouble(Candidate::fusedScore).reversed());
        return fused;
    }

    private static final class Accumulator {
        final KnowledgeChunk chunk;
        double fused;
        Double similarity;
        int bestRank = Integer.MAX_VALUE;

        Accumulator(KnowledgeChunk chunk) {
            this.chunk = chunk;
        }
    }
}
