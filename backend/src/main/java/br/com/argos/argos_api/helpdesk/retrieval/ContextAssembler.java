package br.com.argos.argos_api.helpdesk.retrieval;

import br.com.argos.argos_api.helpdesk.HelpdeskProperties;
import br.com.argos.argos_api.helpdesk.model.KnowledgeChunk;
import br.com.argos.argos_api.helpdesk.model.ParentSection;
import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import br.com.argos.argos_api.helpdesk.retrieval.ReciprocalRankFusion.Candidate;
import br.com.argos.argos_api.helpdesk.store.KnowledgeStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent expansion: the search matched small children; the model reads their parent sections, so it gets the whole
 * explanation (the steps before and after, the heading it belongs to) instead of an isolated fragment.
 *
 * <ul>
 *   <li>Children of the same parent collapse into one passage, ranked by its best child.</li>
 *   <li>A parent longer than {@code maxPassageChars} contributes only its matching children, in reading order.</li>
 *   <li>Passages stop at {@code maxPassages} or when the {@code maxContextChars} budget runs out.</li>
 * </ul>
 */
public class ContextAssembler {

    private static final int MIN_USEFUL_CHARS = 400;

    private final KnowledgeStore store;
    private final int maxPassages;
    private final int maxPassageChars;
    private final int maxContextChars;

    public ContextAssembler(KnowledgeStore store, HelpdeskProperties.Retrieval settings) {
        this(store, settings.getMaxPassages(), settings.getMaxPassageChars(), settings.getMaxContextChars());
    }

    public ContextAssembler(KnowledgeStore store, int maxPassages, int maxPassageChars, int maxContextChars) {
        this.store = store;
        this.maxPassages = maxPassages;
        this.maxPassageChars = maxPassageChars;
        this.maxContextChars = maxContextChars;
    }

    /**
     * @param selected best candidates first
     * @param firstIndex number of the first passage ({@code [1]} for the initial retrieval, higher for tool searches)
     */
    public List<RetrievedPassage> assemble(List<Candidate> selected, int firstIndex) {
        Map<String, List<Candidate>> byParent = new LinkedHashMap<>();
        for (Candidate candidate : selected) {
            byParent.computeIfAbsent(candidate.chunk().parentId(), id -> new ArrayList<>()).add(candidate);
        }
        Map<String, ParentSection> parents = store.parents(byParent.keySet());

        List<RetrievedPassage> passages = new ArrayList<>();
        int remaining = maxContextChars;
        for (Map.Entry<String, List<Candidate>> entry : byParent.entrySet()) {
            if (passages.size() >= maxPassages || remaining < MIN_USEFUL_CHARS) {
                break;
            }
            List<KnowledgeChunk> matches = entry.getValue().stream().map(Candidate::chunk)
                    .sorted(Comparator.comparingInt(KnowledgeChunk::position)).toList();
            ParentSection parent = parents.getOrDefault(entry.getKey(), fromChildren(matches));
            String content = parent.content().length() <= maxPassageChars ? parent.content() : joinChildren(matches);
            if (content.length() > remaining) {
                content = content.substring(0, remaining) + "…";
            }
            remaining -= content.length();
            double best = entry.getValue().stream().mapToDouble(Candidate::relevance).max().orElse(0);
            passages.add(new RetrievedPassage(firstIndex + passages.size(), parent, content, matches, best));
        }
        return passages;
    }

    private static String joinChildren(List<KnowledgeChunk> matches) {
        return String.join("\n\n[…]\n\n", matches.stream().map(KnowledgeChunk::body).toList());
    }

    /** Parent missing from the store (indexing in progress): fall back to what the children say. */
    private static ParentSection fromChildren(List<KnowledgeChunk> matches) {
        KnowledgeChunk first = matches.get(0);
        return new ParentSection(first.parentId(), first.docId(), first.docTitle(), first.sectionPath(), first.source(),
                joinChildren(matches), first.position());
    }
}
