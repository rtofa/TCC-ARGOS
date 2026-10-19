package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.apikey.ApiKeyPrincipal;
import br.com.argos.argos_api.delivery.domain.Deploy;
import br.com.argos.argos_api.delivery.domain.DeployRepository;
import br.com.argos.argos_api.delivery.dto.DeployResponse;
import br.com.argos.argos_api.delivery.dto.RecordDeployRequest;
import br.com.argos.argos_api.shared.security.AuthenticatedUser;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
class DeployServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();
    private static final ApiKeyPrincipal KEY = new ApiKeyPrincipal(KEY_ID, ORG, "argos_abc123");
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Mock
    private DeployRepository repository;
    @Mock
    private CurrentUser currentUser;

    private DeployService service;

    @BeforeEach
    void setUp() {
        service = new DeployService(repository, currentUser, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(repository.save(any(Deploy.class))).then(returnsFirstArg());
        lenient().when(currentUser.get()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), ORG));
    }

    private static RecordDeployRequest request(String environment, String commitSha, String url, Instant deployedAt) {
        return new RecordDeployRequest("  checkout-api ", " 2.3.0 ", environment, commitSha, " Ana ", url,
                "  ", deployedAt);
    }

    @Test
    void recordsWithOrganizationFromKeyAndDefaults() {
        DeployResponse response = service.record(KEY, request(null, "ABCDEF1", null, null));

        ArgumentCaptor<Deploy> saved = ArgumentCaptor.forClass(Deploy.class);
        verify(repository).save(saved.capture());
        Deploy deploy = saved.getValue();
        assertThat(deploy.getOrganizationId()).isEqualTo(ORG);
        assertThat(deploy.getApiKeyId()).isEqualTo(KEY_ID);
        assertThat(deploy.getService()).isEqualTo("checkout-api");
        assertThat(deploy.getVersion()).isEqualTo("2.3.0");
        assertThat(deploy.getEnvironment()).isEqualTo("production");
        assertThat(deploy.getCommitSha()).isEqualTo("abcdef1");
        assertThat(deploy.getAuthor()).isEqualTo("Ana");
        assertThat(deploy.getDescription()).isNull();
        assertThat(deploy.getDeployedAt()).isEqualTo(NOW);
        assertThat(deploy.getReceivedAt()).isEqualTo(NOW);
        assertThat(response.service()).isEqualTo("checkout-api");
    }

    @Test
    void keepsReportedTimeAndNormalizesEnvironment() {
        Instant earlier = NOW.minusSeconds(3600);

        service.record(KEY, request(" Staging ", null, "https://github.com/acme/shop/actions/runs/1", earlier));

        ArgumentCaptor<Deploy> saved = ArgumentCaptor.forClass(Deploy.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDeployedAt()).isEqualTo(earlier);
        assertThat(saved.getValue().getReceivedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getEnvironment()).isEqualTo("staging");
        assertThat(saved.getValue().getUrl()).isEqualTo("https://github.com/acme/shop/actions/runs/1");
    }

    @Test
    void acceptsSmallClockSkewButRejectsFutureDeploys() {
        service.record(KEY, request(null, null, null, NOW.plus(DeployService.ALLOWED_CLOCK_SKEW)));

        assertThatThrownBy(() -> service.record(KEY,
                request(null, null, null, NOW.plus(DeployService.ALLOWED_CLOCK_SKEW).plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("futuro");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "ftp://example.com/x", "github.com/acme", "https://", "not a url"})
    void rejectsUrlsThatAreNotHttp(String url) {
        assertThatThrownBy(() -> service.record(KEY, request(null, null, url, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
        verify(repository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listClampsPageSizeAndSortsNewestFirst() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.list(List.of(" checkout-api ", "", "checkout-api"), " Production ", null, null, -1, 1000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(DeployService.MAX_PAGE_SIZE);
        assertThat(pageable.getValue().getSort().getOrderFor("deployedAt").isDescending()).isTrue();
    }

    @Test
    void listRejectsInvertedPeriod() {
        assertThatThrownBy(() -> service.list(null, null, NOW, NOW.minusSeconds(1), 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
