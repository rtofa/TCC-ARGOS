package br.com.argos.argos_api.helpdesk.model;

/** Parent chunk: the whole section (with its headings) handed to the model once one of its children matches. */
public record ParentSection(String parentId, String docId, String docTitle, String sectionPath, String source,
                            String content, int position) {
}
