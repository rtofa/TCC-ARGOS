package br.com.argos.argos_api.ai;

import br.com.argos.argos_api.ai.assistant.ChatMessageRepository.ChatMessageRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class AssistantController {

    record AskRequest(@NotBlank @Size(max = 2000) String question) {
    }

    private final AssistantService assistant;

    AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @GetMapping("/api/incidents/{id}/assistant/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    List<ChatMessageRow> conversation(@PathVariable UUID id) {
        return assistant.conversation(id);
    }

    /** 202: the answer is produced in the background; poll the conversation until it is no longer PENDING. */
    @PostMapping("/api/incidents/{id}/assistant/messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    ResponseEntity<List<ChatMessageRow>> ask(@PathVariable UUID id, @Valid @RequestBody AskRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(assistant.ask(id, request.question()));
    }
}
