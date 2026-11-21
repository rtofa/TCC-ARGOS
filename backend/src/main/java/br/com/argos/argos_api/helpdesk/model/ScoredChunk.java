package br.com.argos.argos_api.helpdesk.model;

/**
 * A chunk returned by a search.
 *
 * @param score      rank score of that search (RRF score for hybrid search, full-text rank for lexical search)
 * @param similarity cosine similarity between the query and the chunk embeddings, or null without embeddings
 */
public record ScoredChunk(KnowledgeChunk chunk, double score, Double similarity) {

    public ScoredChunk(KnowledgeChunk chunk, double score) {
        this(chunk, score, null);
    }
}
