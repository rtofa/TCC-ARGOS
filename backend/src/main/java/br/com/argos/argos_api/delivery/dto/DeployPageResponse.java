package br.com.argos.argos_api.delivery.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Same shape as the incident list page, so clients paginate both the same way. */
public record DeployPageResponse(List<DeployResponse> content, int page, int size, long totalElements,
                                 int totalPages) {

    public static DeployPageResponse from(Page<DeployResponse> page) {
        return new DeployPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
