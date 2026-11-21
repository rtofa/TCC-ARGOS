package br.com.argos.argos_api.helpdesk.model;

import java.time.Instant;
import java.util.List;

/** Health of the support agent: knowledge base, search capabilities and language models. */
public record KnowledgeStatus(boolean storeAvailable, boolean indexing, List<IndexedDocument> documents, long chunks,
                              Capabilities capabilities, IngestionReport lastIngestion, String lastError) {

    public record IndexedDocument(String docId, String title, String source, int parents, int chunks,
                                  boolean embedded, Instant indexedAt) {
    }

    /**
     * @param lexicalSearch  full-text search available (database reachable)
     * @param semanticSearch hybrid search on: embedding model configured and pgvector table ready
     * @param reranking      cross-encoder reranking on
     * @param models         chat models in fallback order, with their circuit state
     */
    public record Capabilities(boolean lexicalSearch, boolean semanticSearch, String embeddingModel,
                               boolean reranking, String rerankModel, List<String> models) {
    }
}
