package br.com.argos.argos_api.alerting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertResult(int index, String dedupKey, AlertAction action, UUID incidentId, String reason) {

    public static AlertResult of(int index, String dedupKey, AlertAction action, UUID incidentId) {
        return new AlertResult(index, dedupKey, action, incidentId, null);
    }

    public static AlertResult rejected(int index, String dedupKey, String reason) {
        return new AlertResult(index, dedupKey, AlertAction.REJECTED, null, reason);
    }
}
