package br.com.argos.argos_api.incident.domain;

public enum EventType {
    OPENED,
    STATUS_CHANGED,
    REOPENED,
    SEVERITY_CHANGED,
    ASSIGNEE_CHANGED,
    COMMENT,
    ALERT_TRIGGERED,
    ALERT_RESOLVED
}
