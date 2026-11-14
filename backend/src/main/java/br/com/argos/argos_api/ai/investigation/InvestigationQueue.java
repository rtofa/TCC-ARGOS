package br.com.argos.argos_api.ai.investigation;

import br.com.argos.argos_api.ai.AiProperties;
import br.com.argos.argos_api.ai.agent.IncidentInvestigator;
import br.com.argos.argos_api.ai.agent.InvestigationOutcome;
import br.com.argos.argos_api.ai.investigation.Investigation.Claim;
import br.com.argos.argos_api.incident.IncidentQueries;
import br.com.argos.argos_api.incident.IncidentQueries.IncidentSnapshot;
import br.com.argos.argos_api.shared.security.SystemActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Asynchronous execution of investigations. A poller claims due items from the database only up to the free
 * capacity of a bounded worker pool (backpressure), workers run them in the organization's security scope, and a
 * watchdog recovers runs that hung or whose instance died.
 */
@Component
public class InvestigationQueue {

    private static final Logger log = LoggerFactory.getLogger(InvestigationQueue.class);

    private final InvestigationRepository repository;
    private final IncidentInvestigator investigator;
    private final IncidentQueries incidents;
    private final ThreadPoolTaskExecutor executor;
    private final AiProperties properties;

    public InvestigationQueue(InvestigationRepository repository, IncidentInvestigator investigator,
                              IncidentQueries incidents, @Qualifier("investigationExecutor") ThreadPoolTaskExecutor executor,
                              AiProperties properties) {
        this.repository = repository;
        this.investigator = investigator;
        this.incidents = incidents;
        this.executor = executor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${argos.ai.poll-interval-ms:2000}", initialDelayString = "${argos.ai.poll-initial-delay-ms:5000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        int free = executor.getMaxPoolSize() - executor.getActiveCount() - executor.getQueueSize();
        List<Claim> claims = repository.claimDue(free, properties.getMaxRunningPerOrganization());
        for (Claim claim : claims) {
            try {
                executor.execute(() -> run(claim));
            } catch (TaskRejectedException e) {
                log.warn("Fila de investigações cheia; {} volta para a fila", claim.id());
                repository.release(claim);
            }
        }
    }

    @Scheduled(fixedDelayString = "${argos.ai.watchdog-interval-ms:60000}", initialDelayString = "${argos.ai.watchdog-interval-ms:60000}")
    public void watchdog() {
        int recovered = repository.recoverStale(properties.getInvestigationTimeout(), properties.getMaxAttempts());
        if (recovered > 0) {
            log.warn("Watchdog recuperou {} investigação(ões) travada(s)", recovered);
        }
    }

    void run(Claim claim) {
        SystemActor.runAs(claim.organizationId(), () -> {
            try {
                Optional<IncidentSnapshot> incident = incidents.find(claim.organizationId(), claim.incidentId());
                if (incident.isEmpty()) {
                    repository.fail(claim, "Incidente não encontrado");
                    return;
                }
                long started = System.nanoTime();
                InvestigationOutcome outcome = investigator.investigate(claim.id(), incident.get());
                if (!repository.complete(claim, outcome)) {
                    log.warn("Resultado da investigação {} descartado: a tentativa {} expirou", claim.id(), claim.attempt());
                    return;
                }
                log.info("Investigação {} concluída em {} ms (modo {}, modelo {}, {} ferramentas, US$ {})", claim.id(),
                        (System.nanoTime() - started) / 1_000_000, outcome.mode(), outcome.model(), outcome.toolCalls(),
                        outcome.costUsd());
            } catch (RuntimeException e) {
                log.error("Investigação {} falhou", claim.id(), e);
                repository.fail(claim, "Erro inesperado: " + e.getMessage());
            }
        });
    }
}
