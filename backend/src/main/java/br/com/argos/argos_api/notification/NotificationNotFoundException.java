package br.com.argos.argos_api.notification;

import java.util.UUID;

class NotificationNotFoundException extends RuntimeException {
    NotificationNotFoundException(UUID id) {
        super("Canal de notificação não encontrado: " + id);
    }
}
