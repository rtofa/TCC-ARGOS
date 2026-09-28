package br.com.argos.argos_api.incident.dto;

import br.com.argos.argos_api.incident.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 10000) String description,
        @NotNull Severity severity,
        @Size(max = 20) List<@NotBlank @Size(max = 100) String> affectedServices,
        UUID assigneeId) {
}
