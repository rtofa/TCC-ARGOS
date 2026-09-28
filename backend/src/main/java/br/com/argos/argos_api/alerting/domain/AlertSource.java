package br.com.argos.argos_api.alerting.domain;

import br.com.argos.argos_api.alerting.dto.AlertSourceRequest;
import br.com.argos.argos_api.alerting.mapping.AlertMapping;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;


@Entity
@Table(name = "alert_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertSource implements Persistable<UUID> {

   
    private static final Map<String, String> BASE_STATUS = Map.of("firing", "firing", "resolved", "resolved");
    private static final Map<String, String> BASE_SEVERITY =
            Map.of("sev1", "SEV1", "sev2", "SEV2", "sev3", "SEV3", "sev4", "SEV4");

    @Id
    private UUID id;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean isNew = true;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "items_path", length = 200)
    private String itemsPath;

    @Column(name = "title_path", nullable = false, length = 200)
    private String titlePath;

    @Column(name = "dedup_key_path", nullable = false, length = 200)
    private String dedupKeyPath;

    @Column(name = "status_path", length = 200)
    private String statusPath;

    @Column(name = "severity_path", length = 200)
    private String severityPath;

    @Column(name = "service_path", length = 200)
    private String servicePath;

    @Column(name = "description_path", length = 200)
    private String descriptionPath;

    @Convert(converter = JsonMapConverter.class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "status_map", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> statusMap = new LinkedHashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "severity_map", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> severityMap = new LinkedHashMap<>();

    @Column(name = "default_severity", nullable = false, length = 10)
    private String defaultSeverity;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static AlertSource create(UUID organizationId, UUID createdBy, AlertSourceRequest request, Instant now) {
        AlertSource source = new AlertSource();
        source.id = UUID.randomUUID();
        source.organizationId = organizationId;
        source.createdBy = createdBy;
        source.createdAt = now;
        source.apply(request, now);
        return source;
    }

    public void update(AlertSourceRequest request, Instant now) {
        apply(request, now);
    }

    public void delete(Instant now) {
        if (deletedAt == null) {
            deletedAt = now;
        }
    }

    public AlertMapping toMapping() {
        return new AlertMapping(itemsPath, titlePath, dedupKeyPath, statusPath, severityPath, servicePath,
                descriptionPath, merged(BASE_STATUS, statusMap), merged(BASE_SEVERITY, severityMap), defaultSeverity);
    }

    private void apply(AlertSourceRequest request, Instant now) {
        name = request.name().trim();
        itemsPath = blankToNull(request.itemsPath());
        titlePath = request.titlePath().trim();
        dedupKeyPath = request.dedupKeyPath().trim();
        statusPath = blankToNull(request.statusPath());
        severityPath = blankToNull(request.severityPath());
        servicePath = blankToNull(request.servicePath());
        descriptionPath = blankToNull(request.descriptionPath());
        statusMap = new LinkedHashMap<>(request.statusMap() == null ? Map.of() : request.statusMap());
        severityMap = new LinkedHashMap<>(request.severityMap() == null ? Map.of() : request.severityMap());
        defaultSeverity = request.defaultSeverity() == null ? "SEV3" : request.defaultSeverity();
        updatedAt = now;
    }

    private static Map<String, String> merged(Map<String, String> base, Map<String, String> custom) {
        Map<String, String> result = new LinkedHashMap<>(base);
        custom.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
