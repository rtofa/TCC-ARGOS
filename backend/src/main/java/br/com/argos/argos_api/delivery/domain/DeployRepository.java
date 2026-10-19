package br.com.argos.argos_api.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface DeployRepository extends JpaRepository<Deploy, UUID>, JpaSpecificationExecutor<Deploy> {
}
