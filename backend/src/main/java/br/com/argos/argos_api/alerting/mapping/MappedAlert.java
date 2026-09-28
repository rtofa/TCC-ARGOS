package br.com.argos.argos_api.alerting.mapping;

import java.util.List;


public record MappedAlert(
        int index,
        String title,
        String dedupKey,
        AlertStatus status,
        String severity,
        List<String> services,
        String description,
        String rejectionReason) {

    public boolean rejected() {
        return rejectionReason != null;
    }
}
