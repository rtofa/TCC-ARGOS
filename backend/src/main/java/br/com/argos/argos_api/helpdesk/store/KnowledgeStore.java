package br.com.argos.argos_api.helpdesk.store;

import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.ParentSection;
import br.com.argos.argos_api.helpdesk.model.ScoredChunk;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Storage and search of the knowledge base.
 *
 * <ul>
 *   <li>Children (small, contextualized chunks) with their embeddings: searched with hybrid search, full-text and
 *       vector similarity in one query, fused with Reciprocal Rank Fusion.</li>
 *   <li>Parents (whole sections): fetched by id once their children match; also searchable by full-text alone,
 *       which is the fallback while embeddings are off.</li>
 * </ul>
 *
 * Implementations throw {@link KnowledgeStoreException} when the store cannot be reached.
 */
public interface KnowledgeStore {

    /**
     * Gets the store ready for indexing.
     *
     * @param semantic also prepare the vector table (embeddings configured)
     * @return true when the vector table was rebuilt (every document must be indexed again)
     */
    boolean prepare(int dimensions, boolean semantic);

    /** Hybrid search is ready (vector table available). */
    boolean hybridReady();

    /** Documents currently indexed, by id. */
    Map<String, StoredDocument> documents();

    /**
     * Replaces everything indexed for a document.
     *
     * @param embeddings one vector per chunk, in the same order, or {@code null} when embeddings are off (then only
     *                   the parents are stored, for full-text search)
     */
    void replaceDocument(StoredDocument document, List<ParentSection> parents, List<KnowledgeChunk> chunks,
                         List<float[]> embeddings);

    void deleteDocuments(Collection<String> docIds);

    /**
     * Hybrid search over the children: full-text ({@code text}) and nearest vectors ({@code vector}) fused with RRF.
     * Each hit carries the fused score and its cosine similarity to the query vector.
     */
    List<ScoredChunk> hybridSearch(String text, float[] vector, int topK);

    /** Full-text search over the parent sections, matching any of the words (fallback without embeddings). */
    List<ScoredChunk> lexicalSearch(String query, int topK);

    Map<String, ParentSection> parents(Collection<String> parentIds);

    long chunkCount();

    boolean available();
}
