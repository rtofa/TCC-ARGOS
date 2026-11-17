package br.com.argos.argos_api.notification.dto;

import br.com.argos.argos_api.notification.domain.NotificationChannel;
import br.com.argos.argos_api.notification.domain.NotificationChannelType;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record NotificationChannelResponse(
        UUID id,
        String name,
        NotificationChannelType type,
        String targetPreview,
        String minSeverity,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        Instant lastNotifiedAt,
        String lastError
) {
    public static NotificationChannelResponse from(NotificationChannel ch) {
        return new NotificationChannelResponse(
                ch.getId(),
                ch.getName(),
                ch.getType(),
                maskTarget(ch.getType(), ch.getTarget()),
                ch.getMinSeverity(),
                ch.isEnabled(),
                ch.getCreatedAt(),
                ch.getUpdatedAt(),
                ch.getLastNotifiedAt(),
                ch.getLastError()
        );
    }

    static String maskTarget(NotificationChannelType type, String target) {
        if (target == null || target.isBlank()) {
            return "";
        }
        if (type == NotificationChannelType.EMAIL) {
            return target;
        }
        try {
            URI uri = URI.create(target);
            String host = uri.getHost() != null ? uri.getHost() : "";
            String suffix = target.length() > 4 ? target.substring(target.length() - 4) : "";
            return uri.getScheme() + "://" + host + "/.../****" + suffix;
        } catch (IllegalArgumentException e) {
            return "****";
        }
    }
}
