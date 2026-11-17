package br.com.argos.argos_api.notification.dto;

import java.time.Instant;

public record NotificationTestResult(boolean delivered, String message, Instant testedAt) {
}
