package br.com.argos.argos_api.delivery.dto;

import br.com.argos.argos_api.delivery.domain.Deploy;

import java.time.Instant;
import java.util.UUID;

public record DeployResponse(
        UUID id,
        String service,
        String version,
        String environment,
        String commitSha,
        String author,
        String url,
        String description,
        Instant deployedAt,
        Instant receivedAt) {

    public static DeployResponse from(Deploy deploy) {
        return new DeployResponse(deploy.getId(), deploy.getService(), deploy.getVersion(), deploy.getEnvironment(),
                deploy.getCommitSha(), deploy.getAuthor(), deploy.getUrl(), deploy.getDescription(),
                deploy.getDeployedAt(), deploy.getReceivedAt());
    }
}
