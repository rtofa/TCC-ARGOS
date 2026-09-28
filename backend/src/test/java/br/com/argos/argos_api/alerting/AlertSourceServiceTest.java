package br.com.argos.argos_api.alerting;

import br.com.argos.argos_api.alerting.domain.AlertSource;
import br.com.argos.argos_api.alerting.domain.AlertSourceRepository;
import br.com.argos.argos_api.alerting.dto.AlertSourceRequest;
import br.com.argos.argos_api.alerting.dto.AlertSourceResponse;
import br.com.argos.argos_api.alerting.mapping.AlertMapping;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertSourceServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @Mock
    private AlertSourceRepository repository;
    @Mock
    private CurrentUser currentUser;

    private AlertSourceService service;

    @BeforeEach
    void setUp() {
        service = new AlertSourceService(repository, currentUser, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(currentUser.get()).thenReturn(new AuthenticatedUser(USER, ORG));
    }

    private static AlertSourceRequest grafana() {
        return new AlertSourceRequest("Grafana", "$.alerts", "$.annotations.summary", "$.fingerprint", "$.status",
                "$.labels.severity", "$.labels.service", "$.annotations.description",
                Map.of("firing", "firing", "resolved", "resolved"), Map.of("Critical", "SEV1", "warning", "SEV3"),
                "SEV4");
    }

    private static AlertSourceRequest with(String titlePath, Map<String, String> severityMap,
                                           Map<String, String> statusMap) {
        return new AlertSourceRequest("x", null, titlePath, "$.id", null, null, null, null,
                statusMap, severityMap, null);
    }

    @Test
    void createsSourceForTheAuthenticatedOrganization() {
        when(repository.save(any(AlertSource.class))).then(returnsFirstArg());

        AlertSourceResponse response = service.create(grafana());

        ArgumentCaptor<AlertSource> saved = ArgumentCaptor.forClass(AlertSource.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(USER);
        assertThat(response.name()).isEqualTo("Grafana");
        assertThat(response.defaultSeverity()).isEqualTo("SEV4");
    }

    @Test
    void rejectsInvalidRules() {
        assertThatThrownBy(() -> service.create(with("$[?(", Map.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("titlePath");
        assertThatThrownBy(() -> service.create(with(" ", Map.of(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(with("$.t", Map.of("critical", "SEV9"), Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("severityMap");
        assertThatThrownBy(() -> service.create(with("$.t", Map.of(), Map.of("down", "broken"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("statusMap");
        verify(repository, never()).save(any());
    }

    @Test
    void updateAndDeleteOfAnotherOrganizationSourceAreNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, grafana())).isInstanceOf(AlertSourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(AlertSourceNotFoundException.class);
    }

    @Test
    void deleteIsLogical() {
        AlertSource source = existing();

        service.delete(source.getId());

        assertThat(source.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void mappingLowercasesKeysAndKeepsStandardValues() {
        AlertMapping mapping = existing().toMapping();

        assertThat(mapping.severityMap()).containsEntry("critical", "SEV1").containsEntry("sev2", "SEV2");
        assertThat(mapping.statusMap()).containsEntry("firing", "firing").containsEntry("resolved", "resolved");
        assertThat(mapping.itemsPath()).isEqualTo("$.alerts");
        assertThat(mapping.defaultSeverity()).isEqualTo("SEV4");
    }

    @Test
    void findsActiveSourceOfTheKeyOrganization() {
        AlertSource source = existing();

        assertThat(service.findActive(ORG, source.getId())).isSameAs(source);
        UUID other = UUID.randomUUID();
        when(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(other, ORG)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findActive(ORG, other)).isInstanceOf(AlertSourceNotFoundException.class);
    }

    private AlertSource existing() {
        AlertSource source = AlertSource.create(ORG, USER, grafana(), NOW.minusSeconds(60));
        lenient().when(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(source.getId(), ORG))
                .thenReturn(Optional.of(source));
        return source;
    }
}
