package br.com.argos.argos_api.incident.domain;

/**
 * Aggregated MTTA/MTTR in seconds; averages are null when no incident has the timestamp.
 */
public interface MetricsRow {

    long getCount();

    Long getMtta();

    Long getMttr();
}
