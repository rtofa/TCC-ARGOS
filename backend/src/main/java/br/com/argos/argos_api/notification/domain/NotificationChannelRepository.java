package br.com.argos.argos_api.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    List<NotificationChannel> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<NotificationChannel> findByOrganizationIdAndEnabledTrue(UUID organizationId);

    Optional<NotificationChannel> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationIdAndEnabledTrue(UUID organizationId);
}
