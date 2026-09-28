package br.com.argos.argos_api.incident;

import java.util.UUID;

public class InvalidAssigneeException extends RuntimeException {

    public InvalidAssigneeException(UUID assigneeId) {
        super("Responsável não é membro da organização: " + assigneeId);
    }
}
