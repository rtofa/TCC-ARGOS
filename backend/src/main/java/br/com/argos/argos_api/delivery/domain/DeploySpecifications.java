package br.com.argos.argos_api.delivery.domain;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class DeploySpecifications {

    private DeploySpecifications() {
    }

    /**
     * Organization is always applied; the other filters are ignored when null or empty.
     * from is inclusive and to exclusive.
     */
    public static Specification<Deploy> matching(UUID organizationId, Collection<String> services,
                                                 String environment, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (services != null && !services.isEmpty()) {
                predicates.add(root.get("service").in(services));
            }
            if (environment != null) {
                predicates.add(cb.equal(root.get("environment"), environment));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("deployedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("deployedAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
