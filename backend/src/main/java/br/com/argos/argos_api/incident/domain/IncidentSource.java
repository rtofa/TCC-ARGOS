package br.com.argos.argos_api.incident.domain;

/**
 * Where an incident came from. ALERT is reserved for alert correlation (MON-09).
 */
public enum IncidentSource {
    MANUAL,
    ALERT
}
