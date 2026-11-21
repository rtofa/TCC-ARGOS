package br.com.argos.argos_api.helpdesk.agent;

import br.com.argos.argos_api.helpdesk.model.RetrievedPassage;
import br.com.argos.argos_api.helpdesk.model.SourceReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The passages one answer may rely on: the initial retrieval plus whatever the documentation tool finds later.
 * Numbering keeps going across searches, so every citation [n] points to exactly one passage.
 */
public class PassageRegistry {

    private final List<RetrievedPassage> passages = new ArrayList<>();
    private final Set<String> parents = new HashSet<>();
    private final int maxPassages;

    public PassageRegistry(List<RetrievedPassage> initial, int maxPassages) {
        this.maxPassages = maxPassages;
        add(initial);
    }

    /** Registers new passages (sections already known are skipped) and returns them renumbered. */
    public synchronized List<RetrievedPassage> add(List<RetrievedPassage> found) {
        List<RetrievedPassage> added = new ArrayList<>();
        for (RetrievedPassage passage : found) {
            if (passages.size() >= maxPassages) {
                break;
            }
            if (parents.add(passage.section().parentId())) {
                RetrievedPassage numbered = passage.withIndex(passages.size() + 1);
                passages.add(numbered);
                added.add(numbered);
            }
        }
        return added;
    }

    public synchronized int nextIndex() {
        return passages.size() + 1;
    }

    public synchronized List<RetrievedPassage> passages() {
        return List.copyOf(passages);
    }

    public synchronized List<SourceReference> references() {
        return passages.stream().map(RetrievedPassage::toReference).toList();
    }
}
