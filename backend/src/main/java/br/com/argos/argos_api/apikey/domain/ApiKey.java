package br.com.argos.argos_api.apikey.domain;

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


@Entity
@Table(name = "api_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean isNew = true;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, updatable = false, length = 20)
    private String prefix;

    @Column(name = "key_hash", nullable = false, updatable = false, length = 64)
    private String keyHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public static ApiKey create(UUID organizationId, String name, String prefix, String keyHash, UUID createdBy,
                                Instant now) {
        ApiKey key = new ApiKey();
        key.id = UUID.randomUUID();
        key.organizationId = organizationId;
        key.name = name.trim();
        key.prefix = prefix;
        key.keyHash = keyHash;
        key.createdBy = createdBy;
        key.createdAt = now;
        return key;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    /** Permanent; revoking again keeps the original date. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
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
