package br.com.argos.argos_api.helpdesk.model;

/**
 * Child chunk: the small, precise unit that is searched (full-text and vectors).
 *
 * @param contextualText what is indexed and embedded: document title and heading path followed by the body, so the
 *                       chunk keeps the context of the section it came from
 */
public record KnowledgeChunk(String chunkId, String parentId, String docId, String docTitle, String sectionPath,
                             String source, String body, String contextualText, int position) {
}
