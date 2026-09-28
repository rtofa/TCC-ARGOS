package br.com.argos.argos_api.alerting.mapping;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertMapperTest {

    private final AlertMapper mapper = new AlertMapper();

    private List<MappedAlert> mapDefault(String body) {
        return mapper.map(body, ArgosDefaultMapping.MAPPING);
    }

    @Test
    void mapsSingleAlertInDefaultFormat() {
        List<MappedAlert> alerts = mapDefault("""
                {"title":" CPU alta ","dedupKey":"cpu-checkout","status":"firing","severity":"critical",
                 "service":"checkout-api","description":"95%"}
                """);

        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.index()).isZero();
            assertThat(alert.title()).isEqualTo("CPU alta");
            assertThat(alert.dedupKey()).isEqualTo("cpu-checkout");
            assertThat(alert.status()).isEqualTo(AlertStatus.FIRING);
            assertThat(alert.severity()).isEqualTo("SEV1");
            assertThat(alert.services()).containsExactly("checkout-api");
            assertThat(alert.description()).isEqualTo("95%");
            assertThat(alert.rejected()).isFalse();
        });
    }

    @Test
    void mapsListOfAlerts() {
        List<MappedAlert> alerts = mapDefault("""
                {"alerts":[{"title":"a","dedupKey":"1"},{"title":"b","dedupKey":"2"},{"title":"c","dedupKey":"3"}]}
                """);

        assertThat(alerts).extracting(MappedAlert::index).containsExactly(0, 1, 2);
        assertThat(alerts).extracting(MappedAlert::title).containsExactly("a", "b", "c");
    }

    @Test
    void translatesSeveritiesCaseInsensitively() {
        assertThat(severityOf("critical")).isEqualTo("SEV1");
        assertThat(severityOf("high")).isEqualTo("SEV2");
        assertThat(severityOf("warning")).isEqualTo("SEV3");
        assertThat(severityOf("info")).isEqualTo("SEV4");
        assertThat(severityOf("SEV2")).isEqualTo("SEV2");
        assertThat(severityOf("Critical")).isEqualTo("SEV1");
        assertThat(severityOf("desconhecida")).isEqualTo("SEV3");
    }

    @Test
    void missingSeverityAndStatusUseDefaults() {
        MappedAlert alert = mapDefault("{\"title\":\"t\",\"dedupKey\":\"k\"}").get(0);

        assertThat(alert.severity()).isEqualTo("SEV3");
        assertThat(alert.status()).isEqualTo(AlertStatus.FIRING);
        assertThat(alert.services()).isEmpty();
    }

    @Test
    void mapsResolvedStatusAndRejectsUnknownStatus() {
        assertThat(mapDefault("{\"title\":\"t\",\"dedupKey\":\"k\",\"status\":\"RESOLVED\"}").get(0).status())
                .isEqualTo(AlertStatus.RESOLVED);

        MappedAlert unknown = mapDefault("{\"title\":\"t\",\"dedupKey\":\"k\",\"status\":\"pending\"}").get(0);
        assertThat(unknown.rejected()).isTrue();
        assertThat(unknown.rejectionReason()).contains("status");
    }

    @Test
    void acceptsServiceAsTextOrList() {
        MappedAlert alert = mapDefault("""
                {"title":"t","dedupKey":"k","service":["checkout-api","payment-api"]}
                """).get(0);

        assertThat(alert.services()).containsExactly("checkout-api", "payment-api");
    }

    @Test
    void rejectsItemsWithoutTitleOrDedupKeyKeepingTheOthers() {
        List<MappedAlert> alerts = mapDefault("""
                {"alerts":[{"dedupKey":"1"},{"title":"ok","dedupKey":"2"},{"title":"sem chave"}]}
                """);

        assertThat(alerts.get(0).rejectionReason()).isEqualTo("title ausente");
        assertThat(alerts.get(1).rejected()).isFalse();
        assertThat(alerts.get(2).rejectionReason()).isEqualTo("dedupKey ausente");
    }

    @Test
    void ignoresOrganizationInformedInThePayload() {
        MappedAlert alert = mapDefault("""
                {"title":"t","dedupKey":"k","organizationId":"00000000-0000-0000-0000-000000000001"}
                """).get(0);

        assertThat(alert.rejected()).isFalse();
    }

    @Test
    void rejectsMoreThan100Alerts() {
        StringBuilder body = new StringBuilder("{\"alerts\":[");
        for (int i = 0; i < 101; i++) {
            body.append(i == 0 ? "" : ",").append("{\"title\":\"t\",\"dedupKey\":\"").append(i).append("\"}");
        }
        body.append("]}");

        assertThatThrownBy(() -> mapDefault(body.toString())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidOrEmptyJson() {
        assertThatThrownBy(() -> mapDefault("{nao é json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapDefault("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapDefault("[1,2]")).isInstanceOf(IllegalArgumentException.class);
    }

    static final AlertMapping GRAFANA = new AlertMapping("$.alerts", "$.annotations.summary", "$.fingerprint",
            "$.status", "$.labels.severity", "$.labels.service", "$.annotations.description",
            Map.of("firing", "firing", "resolved", "resolved"), Map.of("critical", "SEV1", "warning", "SEV3"), "SEV4");

    @Test
    void mapsRealGrafanaPayload() throws Exception {
        String body = new String(getClass().getResourceAsStream("/alerting/grafana-webhook.json").readAllBytes(),
                StandardCharsets.UTF_8);

        List<MappedAlert> alerts = mapper.map(body, GRAFANA);

        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0)).satisfies(alert -> {
            assertThat(alert.title()).isEqualTo("CPU acima de 90% no checkout-api");
            assertThat(alert.dedupKey()).isEqualTo("c6eadffa33fcdf37");
            assertThat(alert.status()).isEqualTo(AlertStatus.FIRING);
            assertThat(alert.severity()).isEqualTo("SEV1");
            assertThat(alert.services()).containsExactly("checkout-api");
            assertThat(alert.description()).isEqualTo("CPU média de 95% nos últimos 5 minutos");
        });
        assertThat(alerts.get(1)).satisfies(alert -> {
            assertThat(alert.dedupKey()).isEqualTo("8a91c2b7d4e5f601");
            assertThat(alert.status()).isEqualTo(AlertStatus.RESOLVED);
            assertThat(alert.severity()).isEqualTo("SEV3");
            assertThat(alert.services()).containsExactly("orders-db");
        });
    }

    @Test
    void severityWithoutTranslationUsesTheSourceDefault() {
        MappedAlert alert = mapper.map("""
                {"alerts":[{"fingerprint":"f","annotations":{"summary":"s"},"labels":{"severity":"info"}}]}
                """, GRAFANA).get(0);

        assertThat(alert.severity()).isEqualTo("SEV4");
    }

    private String severityOf(String value) {
        return mapDefault("{\"title\":\"t\",\"dedupKey\":\"k\",\"severity\":\"" + value + "\"}").get(0).severity();
    }
}
