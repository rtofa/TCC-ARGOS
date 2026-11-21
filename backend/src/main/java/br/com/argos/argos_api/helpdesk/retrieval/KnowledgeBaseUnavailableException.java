package br.com.argos.argos_api.helpdesk.retrieval;

/** The documentation cannot be searched right now (search cluster down or circuit open). Maps to HTTP 503. */
public class KnowledgeBaseUnavailableException extends RuntimeException {

    public KnowledgeBaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
