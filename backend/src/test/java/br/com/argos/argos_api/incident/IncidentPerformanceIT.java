package br.com.argos.argos_api.incident;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SC-005: filtered list and metrics answer in under 2 seconds with 10,000 incidents.
 */
class IncidentPerformanceIT extends AbstractIncidentIT {

    private static final Duration LIMIT = Duration.ofSeconds(2);

    @BeforeEach
    void insertTenThousandIncidents() {
        jdbc.update("""
                INSERT INTO incidents (organization_id, title, severity, status, created_by,
                                       opened_at, acknowledged_at, resolved_at, updated_at)
                SELECT ?, 'Incidente ' || i,
                       (ARRAY['SEV1','SEV2','SEV3','SEV4'])[1 + i % 4],
                       (ARRAY['OPEN','INVESTIGATING','MITIGATED','RESOLVED'])[1 + i % 4],
                       ?,
                       now() - make_interval(mins => i),
                       CASE WHEN i % 4 <> 0 THEN now() - make_interval(mins => i) + interval '5 minutes' END,
                       CASE WHEN i % 4 = 3 THEN now() - make_interval(mins => i) + interval '1 hour' END,
                       now()
                FROM generate_series(1, 10000) AS i
                """, orgA, adminA);
    }

    @Test
    void filteredListIsFast() throws Exception {
        assertFasterThanLimit(get("/api/incidents").with(asAdminA())
                .param("status", "OPEN").param("severity", "SEV1")
                .param("openedFrom", "2020-01-01T00:00:00Z"));
    }

    @Test
    void metricsAreFast() throws Exception {
        assertFasterThanLimit(get("/api/incidents/metrics").with(asAdminA()).param("groupBy", "severity"));
    }

    private void assertFasterThanLimit(RequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isOk()); // warm-up
        long start = System.nanoTime();
        mockMvc.perform(request).andExpect(status().isOk());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(LIMIT);
    }
}
