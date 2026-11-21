package br.com.argos.argos_api.helpdesk.model;

import java.util.List;

/**
 * Context given to the model: a parent section (or, for very long sections, only the matching children) numbered
 * so the answer can cite it as {@code [index]}.
 */
public record RetrievedPassage(int index, ParentSection section, String content, List<KnowledgeChunk> matches,
                               double score) {

    public RetrievedPassage withIndex(int newIndex) {
        return new RetrievedPassage(newIndex, section, content, matches, score);
    }

    public SourceReference toReference() {
        String snippet = matches.isEmpty() ? content : matches.get(0).body();
        String compact = snippet.replaceAll("\\s+", " ").strip();
        return new SourceReference(index, section.docTitle(), section.sectionPath(), section.source(),
                compact.length() > 240 ? compact.substring(0, 240) + "…" : compact, score);
    }
}
