package br.com.argos.argos_api.helpdesk.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A question to the support agent. The conversation lives in the client: it sends the last turns as
 * {@code history}, which is only used to understand follow-ups, never as a source of facts.
 */
public record SupportRequest(
        @NotBlank(message = "a pergunta é obrigatória")
        @Size(min = 2, max = 1000, message = "a pergunta deve ter entre 2 e 1000 caracteres") String question,
        @Size(max = 12, message = "envie no máximo 12 mensagens de histórico") List<@Valid ChatTurn> history) {

    public List<ChatTurn> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}
