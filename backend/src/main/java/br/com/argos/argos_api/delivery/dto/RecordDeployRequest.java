package br.com.argos.argos_api.delivery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * environment defaults to "production" and deployedAt to the moment the deploy is received.
 */
public record RecordDeployRequest(
        @NotBlank @Size(max = 100) String service,
        @NotBlank @Size(max = 100) String version,
        @Size(max = 50) String environment,
        @Pattern(regexp = "[0-9a-fA-F]{7,64}", message = "deve ser um hash hexadecimal de 7 a 64 caracteres")
        String commitSha,
        @Size(max = 200) String author,
        @Size(max = 500) String url,
        @Size(max = 2000) String description,
        Instant deployedAt) {
}
