package br.com.argos.argos_api.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "alert_incident_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertIncidentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    /** Null for the Argos default format. */
    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    @Column(name = "dedup_key", nullable = false, updatable = false, length = 200)
    private String dedupKey;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(nullable = false)
    private int occurrences;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public static AlertIncidentLink open(UUID organizationId, UUID sourceId, String dedupKey, UUID incidentId,
                                         Instant now) {
        AlertIncidentLink link = new AlertIncidentLink();
        link.organizationId = organizationId;
        link.sourceId = sourceId;
        link.dedupKey = dedupKey;
        link.incidentId = incidentId;
        link.occurrences = 1;
        link.firstSeenAt = now;
        link.lastSeenAt = now;
        return link;
    }

    public void registerOccurrence(Instant now) {
        occurrences++;
        lastSeenAt = now;
    }

    public void close(Instant now) {
        if (closedAt == null) {
            closedAt = now;
        }
    }
}
