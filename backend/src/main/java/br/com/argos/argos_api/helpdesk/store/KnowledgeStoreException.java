package br.com.argos.argos_api.helpdesk.store;

/** The knowledge base could not be read or written (search cluster down, circuit open, rejected request). */
public class KnowledgeStoreException extends RuntimeException {

    public KnowledgeStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
