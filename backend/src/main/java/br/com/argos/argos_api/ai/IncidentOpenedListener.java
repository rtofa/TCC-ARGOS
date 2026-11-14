package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.incident.IncidentOpened;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts the automatic investigation when an incident opens. Runs after the incident's transaction commits, in a
 * transaction of its own, and only enqueues work: the incident flow is never slowed down or broken by the AI.
 */
@Component
class IncidentOpenedListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentOpenedListener.class);

    private final InvestigationService investigations;

    IncidentOpenedListener(InvestigationService investigations) {
        this.investigations = investigations;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(IncidentOpened event) {
        try {
            investigations.requestAutomatic(event.organizationId(), event.incidentId());
        } catch (RuntimeException e) {
            log.error("Não foi possível agendar a investigação do incidente {}", event.incidentId(), e);
        }
    }
}
