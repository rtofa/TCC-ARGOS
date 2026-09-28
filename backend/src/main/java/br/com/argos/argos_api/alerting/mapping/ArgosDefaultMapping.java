package br.com.argos.argos_api.alerting.mapping;

import java.util.Map;

/**
 * The Argos default webhook format: one alert, or {"alerts": [...]}.
 */
public final class ArgosDefaultMapping {

    public static final String SOURCE_NAME = "Formato padrão";

    public static final AlertMapping MAPPING = new AlertMapping(
            "$.alerts",
            "$.title",
            "$.dedupKey",
            "$.status",
            "$.severity",
            "$.service",
            "$.description",
            Map.of("firing", "firing", "resolved", "resolved"),
            Map.of("critical", "SEV1", "high", "SEV2", "warning", "SEV3", "info", "SEV4",
                    "sev1", "SEV1", "sev2", "SEV2", "sev3", "SEV3", "sev4", "SEV4"),
            "SEV3");

    private ArgosDefaultMapping() {
    }
}
