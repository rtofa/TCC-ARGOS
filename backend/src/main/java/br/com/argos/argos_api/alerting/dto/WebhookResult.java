package br.com.argos.argos_api.alerting.dto;

import java.util.List;

public record WebhookResult(int received, int accepted, int rejected, List<AlertResult> results) {

    public static WebhookResult of(List<AlertResult> results) {
        int rejected = (int) results.stream().filter(result -> result.action() == AlertAction.REJECTED).count();
        return new WebhookResult(results.size(), results.size() - rejected, rejected, results);
    }
}
