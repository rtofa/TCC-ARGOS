package br.com.argos.argos_api.notification.dto;

import br.com.argos.argos_api.notification.domain.NotificationChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NotificationChannelRequest(
        @NotBlank(message = "nome é obrigatório")
        @Size(max = 100, message = "nome deve ter no máximo 100 caracteres")
        String name,

        @NotNull(message = "tipo é obrigatório (SLACK, DISCORD ou EMAIL)")
        NotificationChannelType type,

        @Size(max = 500, message = "destino deve ter no máximo 500 caracteres")
        String target,

        @Pattern(regexp = "SEV1|SEV2|SEV3|SEV4", message = "minSeverity deve ser SEV1, SEV2, SEV3 ou SEV4")
        String minSeverity,

        Boolean enabled
) {
}
