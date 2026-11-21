package br.com.argos.argos_api.helpdesk.model;

/** How the support agent ended a question. */
public enum Outcome {
    /** Answer grounded in the official documents, with citations. */
    ANSWERED,
    /** The documents do not cover the question (or the draft answer was not supported by them). */
    NOT_FOUND,
    /** The question is not about the Argos platform. */
    OUT_OF_SCOPE,
    /** Blocked by the input guardrail (prompt injection, abuse). */
    BLOCKED,
    /** Greeting or thanks: a short reply without retrieval. */
    GREETING,
    /** No language model could answer; the relevant documentation excerpts are returned instead. */
    DEGRADED
}
