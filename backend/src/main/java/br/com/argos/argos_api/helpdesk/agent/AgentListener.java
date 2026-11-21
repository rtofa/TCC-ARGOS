package br.com.argos.argos_api.helpdesk.agent;

import br.com.argos.argos_api.helpdesk.model.SourceReference;

import java.util.List;

/** Progress of one execution, pushed to the client while it runs (server-sent events). */
public interface AgentListener {

    AgentListener NONE = new AgentListener() {
    };

    /** A pipeline stage started: guardrail, analysis, retrieval, generation, validation. */
    default void onStage(String stage) {
    }

    /** Passages that will be given to the model (sent before the answer starts streaming). */
    default void onSources(List<SourceReference> sources) {
    }

    /** A piece of the answer as the model writes it. */
    default void onToken(String token) {
    }

    /** True once the client went away: the agent stops at the next stage instead of spending tokens. */
    default boolean cancelled() {
        return false;
    }
}
