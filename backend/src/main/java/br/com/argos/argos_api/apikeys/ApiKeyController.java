package br.com.argos.argos_api.apikeys;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/apikeys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXECUTIVE')")
    public ResponseEntity<GeneratedApiKey> createKey(@RequestBody ApiKeyRequest request) {
        return ResponseEntity.ok(apiKeyService.generateKey(request.name()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EDITOR', 'VIEWER', 'EXECUTIVE')")
    public ResponseEntity<List<ApiKeyResponse>> listKeys() {
        return ResponseEntity.ok(apiKeyService.listKeys().stream().map(ApiKeyResponse::fromEntity).toList());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<Void> revokeKey(@PathVariable UUID id) {
        apiKeyService.revokeKey(id);
        return ResponseEntity.noContent().build();
    }
}
