package br.com.argos.argos_api.helpdesk.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One indexed source document.
 *
 * @param hash      fingerprint of the content, the chunking settings and the embedding model: when it is unchanged
 *                  the document is not indexed again
 * @param indexedAt ISO-8601 instant
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredDocument(String docId, String title, String source, String hash, int parents, int chunks,
                             boolean embedded, String embeddingModel, String indexedAt) {
}
