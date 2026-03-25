package com.cpass.platform.client.infra;

import com.cpass.platform.client.domain.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    Optional<ApiKeyEntity> findByApiKeyAndActiveTrue(String apiKey);

    List<ApiKeyEntity> findByClient_IdAndActiveTrue(UUID clientId);
}
