package br.com.argos.argos_api.notification;

import br.com.argos.argos_api.incident.IncidentOpened;
import br.com.argos.argos_api.shared.security.SystemActor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MON-08 / INT-05: Escuta o evento {@link IncidentOpened} após commit e dispara notificações
 * externas (Slack, Discord, E-mail) de forma assíncrona para nunca impactar a latência da abertura.
 */
@Component
class IncidentNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentNotificationListener.class);

    private final NotificationService notificationService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "argos-notifier");
        t.setDaemon(true);
        return t;
    });

    IncidentNotificationListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(IncidentOpened event) {
        executor.submit(() -> {
            try {
                SystemActor.runAs(event.organizationId(),
                        () -> notificationService.notifyIncidentOpened(event.organizationId(), event.incidentId()));
            } catch (RuntimeException e) {
                log.error("Erro inesperado ao notificar abertura do incidente {}", event.incidentId(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
