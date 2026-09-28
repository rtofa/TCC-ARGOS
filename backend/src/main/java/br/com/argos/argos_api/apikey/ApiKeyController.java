package br.com.argos.argos_api.apikey;

import br.com.argos.argos_api.apikey.dto.ApiKeyResponse;
import br.com.argos.argos_api.apikey.dto.CreateApiKeyRequest;
import br.com.argos.argos_api.apikey.dto.CreatedApiKeyResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/api-keys")
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse create(@Valid @RequestBody CreateApiKeyRequest request) {
        return apiKeyService.create(request);
    }

    @GetMapping
    public List<ApiKeyResponse> list() {
        return apiKeyService.list();
    }

    @PostMapping("/{id}/revoke")
    public ApiKeyResponse revoke(@PathVariable UUID id) {
        return apiKeyService.revoke(id);
    }
}
