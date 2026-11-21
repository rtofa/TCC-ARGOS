package br.com.argos.argos_api.helpdesk.model;

/**
 * A documentation excerpt given to the model, as cited in the answer ({@code [index]}).
 *
 * @param section breadcrumb of headings inside the document ("Severidades > SEV1"); blank for the document intro
 * @param score   relevance used to rank it (rerank score when available, otherwise the fused rank score)
 */
public record SourceReference(int index, String document, String section, String source, String snippet, double score) {
}
