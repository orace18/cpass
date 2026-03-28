package com.cpass.platform.email.infra;

import com.cpass.platform.email.domain.ClientEmailConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClientEmailConfigRepository extends JpaRepository<ClientEmailConfigEntity, UUID> {

    Optional<ClientEmailConfigEntity> findByClientId(UUID clientId);
}
