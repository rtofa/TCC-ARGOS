package br.com.argos.argos_api.helpdesk.model;

import java.util.List;

/**
 * What the agent understood from the question.
 *
 * @param standaloneQuestion the question rewritten to stand on its own (follow-ups resolved with the history)
 * @param searchQueries      queries for retrieval, the standalone question first, then reformulations
 * @param byModel            false when the heuristic fallback was used (no model available or it failed)
 */
public record QueryAnalysis(Intent intent, String standaloneQuestion, List<String> searchQueries, boolean byModel) {

    public enum Intent { PLATFORM_QUESTION, GREETING, OUT_OF_SCOPE }
}
