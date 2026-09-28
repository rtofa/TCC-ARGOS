package br.com.argos.argos_api.alerting.mapping;

import java.util.Map;


public record AlertMapping(
        String itemsPath,
        String titlePath,
        String dedupKeyPath,
        String statusPath,
        String severityPath,
        String servicePath,
        String descriptionPath,
        Map<String, String> statusMap,
        Map<String, String> severityMap,
        String defaultSeverity) {
}
