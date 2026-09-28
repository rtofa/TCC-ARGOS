package br.com.argos.argos_api.incident.dto;

import br.com.argos.argos_api.incident.domain.IncidentStatus;
import br.com.argos.argos_api.incident.domain.Severity;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Partial update: null fields are left unchanged.
 */
public record UpdateIncidentRequest(
        @Size(max = 200) @Pattern(regexp = ".*\\S.*", message = "não pode ser vazio") String title,
        @Size(max = 10000) String description,
        Severity severity,
        IncidentStatus status,
        @Size(max = 20) List<@Pattern(regexp = ".*\\S.*", message = "não pode ser vazio") @Size(max = 100) String> affectedServices) {

    public boolean isEmpty() {
        return title == null && description == null && severity == null && status == null && affectedServices == null;
    }
}
