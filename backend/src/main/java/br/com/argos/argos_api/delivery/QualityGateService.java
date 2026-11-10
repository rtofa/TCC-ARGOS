package br.com.argos.argos_api.delivery;

import br.com.argos.argos_api.delivery.dto.QualityGateResponse;
import br.com.argos.argos_api.delivery.dto.QualityGateResponse.BlockingIncident;
import br.com.argos.argos_api.shared.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Quadro 4 / Seção 3.4.3: Avalia o Quality Gate de deploy consultando se há incidente SEV1 aberto
 * ou monitor automático em alerta crítico para o serviço (ou para a organização).
 */
@Service
@Transactional(readOnly = true)
public class QualityGateService {

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final Clock clock;

    public QualityGateService(JdbcTemplate jdbc, CurrentUser currentUser, Clock clock) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public QualityGateResponse evaluateForCurrentUser(String service) {
        return evaluate(currentUser.get().organizationId(), service);
    }

    public QualityGateResponse evaluate(UUID organizationId, String service) {
        String cleanService = service != null && !service.isBlank() ? service.trim() : null;
        List<Object> params = new ArrayList<>(List.of(organizationId));
        String serviceClause = "";
        if (cleanService != null) {
            serviceClause = " AND ? = ANY(affected_services)";
            params.add(cleanService);
        }

        List<BlockingIncident> blocking = jdbc.query("""
                SELECT id, title, severity, status, source, affected_services, opened_at
                FROM incidents
                WHERE organization_id = ?
                  AND status IN ('OPEN', 'INVESTIGATING', 'MITIGATED')
                  AND (severity = 'SEV1' OR (severity = 'SEV2' AND source = 'ALERT'))""" + serviceClause + """

                ORDER BY CASE WHEN severity = 'SEV1' THEN 0 ELSE 1 END, opened_at DESC
                """, (rs, row) -> new BlockingIncident(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("source"),
                strings(rs.getArray("affected_services")),
                rs.getTimestamp("opened_at").toInstant()), params.toArray());

        long sev1Count = blocking.stream().filter(i -> "SEV1".equals(i.severity())).count();
        long criticalAlertCount = blocking.stream().filter(i -> "ALERT".equals(i.source())).count();
        boolean allowed = blocking.isEmpty();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        String reason = allowed
                ? "Aprovado no Quality Gate: nenhum incidente SEV1 aberto ou monitor em alerta crítico"
                        + (cleanService != null ? " para o serviço '" + cleanService + "'." : ".")
                : "Deploy bloqueado pelo Quality Gate: " + sev1Count + " incidente(s) SEV1 ativo(s) e "
                        + criticalAlertCount + " monitor(es) em alerta crítico"
                        + (cleanService != null ? " afetando '" + cleanService + "'." : ".");

        return new QualityGateResponse(
                allowed,
                allowed ? "PASSED" : "BLOCKED",
                cleanService,
                sev1Count,
                criticalAlertCount,
                reason,
                blocking,
                now);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
