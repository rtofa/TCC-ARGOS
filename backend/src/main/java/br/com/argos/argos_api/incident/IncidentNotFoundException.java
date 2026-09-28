package br.com.argos.argos_api.incident;

import java.util.UUID;

public class IncidentNotFoundException extends RuntimeException {

    public IncidentNotFoundException(UUID id) {
        super("Incidente não encontrado: " + id);
    }
}
