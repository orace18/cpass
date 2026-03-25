package com.cpass.platform.client.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.dto.ApiKeyResponse;
import com.cpass.platform.client.dto.ClientProvisionResponse;
import com.cpass.platform.client.dto.CreateClientRequest;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ClientService {

    private final ClientRepository clientRepository;
    private final ApiKeyService apiKeyService;

    public ClientService(ClientRepository clientRepository, ApiKeyService apiKeyService) {
        this.clientRepository = clientRepository;
        this.apiKeyService = apiKeyService;
    }

    @Transactional
    public ClientProvisionResponse createClient(CreateClientRequest request) {
        clientRepository.findByName(request.name()).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "CLIENT_EXISTS", "Client already exists");
        });

        if (request.smsSoftQuota() > request.smsHardQuota() || request.whatsappSoftQuota() > request.whatsappHardQuota()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA", "Soft quota must be <= hard quota");
        }

        ClientEntity client = new ClientEntity();
        client.setId(UUID.randomUUID());
        client.setName(request.name());
        client.setWebhookUrl(request.webhookUrl());
        client.setSmsSoftQuota(request.smsSoftQuota());
        client.setSmsHardQuota(request.smsHardQuota());
        client.setWhatsappSoftQuota(request.whatsappSoftQuota());
        client.setWhatsappHardQuota(request.whatsappHardQuota());
        client.setRateLimitPerMinute(request.rateLimitPerMinute());
        ClientEntity saved = clientRepository.save(client);

        ApiKeyResponse keyResponse = apiKeyService.createApiKey(saved, "default");
        return new ClientProvisionResponse(saved.getId(), saved.getName(), keyResponse.apiKey(), keyResponse.apiSecret());
    }

    @Transactional(readOnly = true)
    public ClientEntity getClient(UUID clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found"));
    }

    @Transactional
    public ApiKeyResponse rotateApiKey(UUID clientId, String label) {
        ClientEntity client = getClient(clientId);
        return apiKeyService.createApiKey(client, label);
    }
}
