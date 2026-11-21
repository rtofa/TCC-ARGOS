package br.com.argos.argos_api.helpdesk.model;

import java.util.List;

/** A source document split into parent sections and the child chunks that point to them. */
public record ChunkedDocument(String docId, String title, String source, List<ParentSection> parents,
                              List<KnowledgeChunk> chunks) {
}
