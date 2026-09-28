package br.com.argos.argos_api.alerting.dto;

import br.com.argos.argos_api.alerting.domain.AlertSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlertSourceResponse(
        UUID id,
        String name,
        String webhookPath,
        String itemsPath,
        String titlePath,
        String dedupKeyPath,
        String statusPath,
        String severityPath,
        String servicePath,
        String descriptionPath,
        Map<String, String> statusMap,
        Map<String, String> severityMap,
        String defaultSeverity,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static AlertSourceResponse from(AlertSource source) {
        return new AlertSourceResponse(source.getId(), source.getName(), "/api/webhooks/alerts/" + source.getId(),
                source.getItemsPath(), source.getTitlePath(), source.getDedupKeyPath(), source.getStatusPath(),
                source.getSeverityPath(), source.getServicePath(), source.getDescriptionPath(),
                Map.copyOf(source.getStatusMap()), Map.copyOf(source.getSeverityMap()), source.getDefaultSeverity(),
                source.getCreatedBy(), source.getCreatedAt(), source.getUpdatedAt());
    }
}
