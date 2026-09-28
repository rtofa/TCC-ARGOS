package br.com.argos.argos_api.apikey;

import java.util.UUID;

public class ApiKeyNotFoundException extends RuntimeException {

    public ApiKeyNotFoundException(UUID id) {
        super("Chave de API não encontrada: " + id);
    }
}
