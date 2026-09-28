package br.com.argos.argos_api.alerting;

import java.util.UUID;

public class AlertSourceNotFoundException extends RuntimeException {

    public AlertSourceNotFoundException(UUID id) {
        super("Fonte de alerta não encontrada: " + id);
    }
}
