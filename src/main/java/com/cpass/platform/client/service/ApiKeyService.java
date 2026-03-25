package com.cpass.platform.client.service;

import com.cpass.platform.client.domain.ApiKeyEntity;
import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.dto.ApiKeyResponse;
import com.cpass.platform.client.infra.ApiKeyRepository;
import com.cpass.platform.common.security.SecretCryptoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final SecretCryptoService secretCryptoService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository, SecretCryptoService secretCryptoService) {
        this.apiKeyRepository = apiKeyRepository;
        this.secretCryptoService = secretCryptoService;
    }

    @Transactional
    public ApiKeyResponse createApiKey(ClientEntity client, String label) {
        String key = "cp_" + randomToken(24);
        String secret = "cs_" + randomToken(48);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setClient(client);
        entity.setApiKey(key);
        entity.setSecretEncrypted(secretCryptoService.encrypt(secret));
        entity.setLabel(label);
        ApiKeyEntity saved = apiKeyRepository.save(entity);

        return new ApiKeyResponse(saved.getId(), key, secret, saved.getLabel(), saved.getCreatedAt());
    }

    private String randomToken(int bytes) {
        byte[] payload = new byte[bytes];
        secureRandom.nextBytes(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }
}
