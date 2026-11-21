package br.com.argos.argos_api.helpdesk;

/** Every slot of the bulkhead is taken. Maps to HTTP 429 with Retry-After. */
public class HelpdeskBusyException extends RuntimeException {

    public HelpdeskBusyException() {
        super("O assistente está atendendo muitas perguntas ao mesmo tempo. Tente novamente em alguns segundos.");
    }
}
