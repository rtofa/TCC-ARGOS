package br.com.argos.argos_api.delivery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A deploy reported by a pipeline. Immutable: a new deploy of the same version is a new row.
 */
@Entity
@Table(name = "deploys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deploy implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean isNew = true;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false, length = 100)
    private String service;

    @Column(nullable = false, updatable = false, length = 100)
    private String version;

    @Column(nullable = false, updatable = false, length = 50)
    private String environment;

    @Column(name = "commit_sha", updatable = false, length = 64)
    private String commitSha;

    @Column(updatable = false, length = 200)
    private String author;

    @Column(updatable = false, length = 500)
    private String url;

    @Column(updatable = false, length = 2000)
    private String description;

    @Column(name = "deployed_at", nullable = false, updatable = false)
    private Instant deployedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "api_key_id", nullable = false, updatable = false)
    private UUID apiKeyId;

    /** Values are expected to be validated and normalized already. */
    public static Deploy record(UUID organizationId, UUID apiKeyId, String service, String version,
                                String environment, String commitSha, String author, String url,
                                String description, Instant deployedAt, Instant receivedAt) {
        Deploy deploy = new Deploy();
        deploy.id = UUID.randomUUID();
        deploy.organizationId = organizationId;
        deploy.apiKeyId = apiKeyId;
        deploy.service = service;
        deploy.version = version;
        deploy.environment = environment;
        deploy.commitSha = commitSha;
        deploy.author = author;
        deploy.url = url;
        deploy.description = description;
        deploy.deployedAt = deployedAt;
        deploy.receivedAt = receivedAt;
        return deploy;
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
