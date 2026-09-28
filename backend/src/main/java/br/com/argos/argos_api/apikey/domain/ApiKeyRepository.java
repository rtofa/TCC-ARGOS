package br.com.argos.argos_api.apikey.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ApiKey> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** Records usage at most once per threshold window to avoid a write per request. */
    @Modifying
    @Query("""
            UPDATE ApiKey k SET k.lastUsedAt = :now
            WHERE k.id = :id AND (k.lastUsedAt IS NULL OR k.lastUsedAt < :threshold)
            """)
    int touchLastUsed(@Param("id") UUID id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
