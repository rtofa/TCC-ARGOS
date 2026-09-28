package br.com.argos.argos_api.incident.domain;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class IncidentSpecifications {

    private IncidentSpecifications() {
    }

    /**
     * Organization is always applied; the other filters are ignored when null or empty.
     * openedFrom is inclusive and openedTo exclusive.
     */
    public static Specification<Incident> matching(UUID organizationId, Collection<IncidentStatus> statuses,
                                                   Collection<Severity> severities, Instant openedFrom,
                                                   Instant openedTo) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (severities != null && !severities.isEmpty()) {
                predicates.add(root.get("severity").in(severities));
            }
            if (openedFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("openedAt"), openedFrom));
            }
            if (openedTo != null) {
                predicates.add(cb.lessThan(root.get("openedAt"), openedTo));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
