package br.com.argos.argos_api.helpdesk.model;

import java.time.Instant;
import java.util.List;

/** Result of (re)indexing the knowledge base. */
public record IngestionReport(Instant finishedAt, long durationMs, int documents, int indexed, int unchanged,
                              int removed, int parents, int chunks, boolean embeddings, boolean indexRebuilt,
                              List<String> warnings) {
}
