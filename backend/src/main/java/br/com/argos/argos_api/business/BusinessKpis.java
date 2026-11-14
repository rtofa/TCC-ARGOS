package br.com.argos.argos_api.business;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** NEG-01 / NEG-02 storage: business KPIs and how much each service weighs on each of them. */
@Repository
public class BusinessKpis {

    public record Kpi(UUID id, String name, String description, String unit, BigDecimal baselinePerMinute,
                      BigDecimal valuePerUnit, List<ServiceWeight> services, Instant updatedAt) {
    }

    public record ServiceWeight(UUID id, String service, BigDecimal impactPercent) {
    }

    /** A service-to-KPI link with the KPI's numbers, as used by the impact estimate. */
    public record Link(UUID kpiId, String kpiName, String unit, BigDecimal baselinePerMinute, BigDecimal valuePerUnit,
                       String service, BigDecimal impactPercent) {
    }

    private static final RowMapper<Link> LINK = (rs, i) -> new Link(rs.getObject("kpi_id", UUID.class),
            rs.getString("name"), rs.getString("unit"), rs.getBigDecimal("baseline_per_minute"),
            rs.getBigDecimal("value_per_unit"), rs.getString("service"), rs.getBigDecimal("impact_percent"));

    private final JdbcTemplate jdbc;

    public BusinessKpis(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Kpi> list(UUID organizationId) {
        List<Kpi> kpis = jdbc.query("""
                SELECT id, name, description, unit, baseline_per_minute, value_per_unit, updated_at
                FROM business_kpis WHERE organization_id = ? ORDER BY name
                """, (rs, i) -> new Kpi(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getString("unit"), rs.getBigDecimal("baseline_per_minute"), rs.getBigDecimal("value_per_unit"), List.of(),
                rs.getTimestamp("updated_at").toInstant()), organizationId);
        List<Link> links = links(organizationId, null);
        return kpis.stream().map(kpi -> new Kpi(kpi.id(), kpi.name(), kpi.description(), kpi.unit(),
                kpi.baselinePerMinute(), kpi.valuePerUnit(), links.stream()
                        .filter(link -> link.kpiId().equals(kpi.id()))
                        .map(link -> new ServiceWeight(null, link.service(), link.impactPercent()))
                        .toList(), kpi.updatedAt())).toList();
    }

    public Optional<Kpi> find(UUID organizationId, UUID kpiId) {
        return list(organizationId).stream().filter(kpi -> kpi.id().equals(kpiId)).findFirst();
    }

    public UUID create(UUID organizationId, String name, String description, String unit, BigDecimal baselinePerMinute,
                       BigDecimal valuePerUnit) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO business_kpis (id, organization_id, name, description, unit, baseline_per_minute, "
                    + "value_per_unit) VALUES (?, ?, ?, ?, ?, ?, ?)", id, organizationId, name, description, unit,
                    baselinePerMinute, valuePerUnit);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Já existe um KPI chamado \"" + name + "\"");
        }
        return id;
    }

    public boolean update(UUID organizationId, UUID kpiId, String name, String description, String unit,
                          BigDecimal baselinePerMinute, BigDecimal valuePerUnit) {
        try {
            return jdbc.update("UPDATE business_kpis SET name = ?, description = ?, unit = ?, baseline_per_minute = ?, "
                    + "value_per_unit = ?, updated_at = NOW() WHERE id = ? AND organization_id = ?", name, description, unit,
                    baselinePerMinute, valuePerUnit, kpiId, organizationId) == 1;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Já existe um KPI chamado \"" + name + "\"");
        }
    }

    public boolean delete(UUID organizationId, UUID kpiId) {
        return jdbc.update("DELETE FROM business_kpis WHERE id = ? AND organization_id = ?", kpiId, organizationId) == 1;
    }

    /** Replaces the service weights of a KPI (the console edits them as one list). */
    public void replaceServices(UUID organizationId, UUID kpiId, List<ServiceWeight> services) {
        jdbc.update("DELETE FROM business_service_kpis WHERE kpi_id = ? AND organization_id = ?", kpiId, organizationId);
        for (ServiceWeight weight : services) {
            jdbc.update("INSERT INTO business_service_kpis (id, organization_id, kpi_id, service, impact_percent) "
                    + "VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), organizationId, kpiId, weight.service(), weight.impactPercent());
        }
    }

    /** Links of the given services (ignoring case), or of every service when {@code services} is null. */
    public List<Link> links(UUID organizationId, Collection<String> services) {
        String sql = """
                SELECT k.id AS kpi_id, k.name, k.unit, k.baseline_per_minute, k.value_per_unit, s.service, s.impact_percent
                FROM business_service_kpis s JOIN business_kpis k ON k.id = s.kpi_id AND k.organization_id = s.organization_id
                WHERE s.organization_id = ?
                """;
        if (services == null) {
            return jdbc.query(sql + " ORDER BY k.name, s.service", LINK, organizationId);
        }
        if (services.isEmpty()) {
            return List.of();
        }
        String[] lowered = services.stream().map(service -> service.toLowerCase(Locale.ROOT)).toArray(String[]::new);
        return jdbc.query(sql + " AND lower(s.service) = ANY (?) ORDER BY k.name, s.service", LINK, organizationId,
                lowered);
    }
}
