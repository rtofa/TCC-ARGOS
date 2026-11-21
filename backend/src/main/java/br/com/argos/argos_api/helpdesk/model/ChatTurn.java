package br.com.argos.argos_api.helpdesk.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One earlier message of the conversation, sent by the client so the agent can resolve follow-up questions. */
public record ChatTurn(
        @NotNull(message = "papel é obrigatório (USER ou ASSISTANT)") Role role,
        @NotBlank(message = "conteúdo é obrigatório")
        @Size(max = 4000, message = "cada mensagem do histórico deve ter no máximo 4000 caracteres") String content) {

    public enum Role { USER, ASSISTANT }
}
