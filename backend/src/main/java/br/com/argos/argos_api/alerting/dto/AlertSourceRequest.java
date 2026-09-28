package br.com.argos.argos_api.alerting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;


public record AlertSourceRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 200) String itemsPath,
        @NotBlank @Size(max = 200) String titlePath,
        @NotBlank @Size(max = 200) String dedupKeyPath,
        @Size(max = 200) String statusPath,
        @Size(max = 200) String severityPath,
        @Size(max = 200) String servicePath,
        @Size(max = 200) String descriptionPath,
        Map<String, String> statusMap,
        Map<String, String> severityMap,
        String defaultSeverity) {
}
