package br.com.argos.argos_api.helpdesk.ingestion;

import br.com.argos.argos_api.helpdesk.HelpdeskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Indexes the knowledge base when the application is ready, in the background. If the search cluster is not up yet,
 * it retries with exponential backoff, so the API starts (and the rest of Argos works) without it.
 */
public class StartupIngestion {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestion.class);

    private final KnowledgeBaseIngestor ingestor;
    private final HelpdeskProperties.KnowledgeBase settings;
    private final ExecutorService executor;
    private volatile Future<?> task;

    public StartupIngestion(KnowledgeBaseIngestor ingestor, HelpdeskProperties.KnowledgeBase settings,
                            ExecutorService executor) {
        this.ingestor = ingestor;
        this.settings = settings;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!settings.isIngestOnStartup()) {
            return;
        }
        task = executor.submit(this::ingestWithRetries);
    }

    private void ingestWithRetries() {
        Duration delay = settings.getRetryInitialDelay();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ingestor.ingest(false);
                return;
            } catch (KnowledgeBaseIngestor.IngestionInProgressException e) {
                return; // someone asked for a reindex meanwhile
            } catch (RuntimeException e) {
                log.warn("Indexação da base de conhecimento falhou ({}); nova tentativa em {} s", e.getMessage(),
                        delay.toSeconds());
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = delay.multipliedBy(2);
            if (delay.compareTo(settings.getRetryMaxDelay()) > 0) {
                delay = settings.getRetryMaxDelay();
            }
        }
    }

    public void stop() {
        Future<?> running = task;
        if (running != null) {
            running.cancel(true);
        }
    }
}
