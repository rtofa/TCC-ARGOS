package br.com.argos.argos_api.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_channels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannelType type;

    @Column(nullable = false, length = 500)
    private String target;

    @Column(name = "min_severity", nullable = false, length = 10)
    private String minSeverity;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    public static NotificationChannel create(UUID organizationId, UUID createdBy, String name,
                                             NotificationChannelType type, String target,
                                             String minSeverity, boolean enabled, Instant now) {
        NotificationChannel ch = new NotificationChannel();
        ch.organizationId = organizationId;
        ch.createdBy = createdBy;
        ch.name = name;
        ch.type = type;
        ch.target = target;
        ch.minSeverity = minSeverity;
        ch.enabled = enabled;
        ch.createdAt = now;
        ch.updatedAt = now;
        return ch;
    }

    public void update(String name, NotificationChannelType type, String target,
                       String minSeverity, boolean enabled, Instant now) {
        this.name = name;
        this.type = type;
        if (target != null && !target.isBlank()) {
            this.target = target;
        }
        this.minSeverity = minSeverity;
        this.enabled = enabled;
        this.updatedAt = now;
    }

    public void markDelivered(Instant at) {
        this.lastNotifiedAt = at;
        this.lastError = null;
    }

    public void markFailed(String error) {
        if (error != null && error.length() > 500) {
            this.lastError = error.substring(0, 500);
        } else {
            this.lastError = error;
        }
    }
}
