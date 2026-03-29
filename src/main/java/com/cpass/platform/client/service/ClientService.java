package com.cpass.platform.client.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.domain.ClientScope;
import com.cpass.platform.client.dto.ClientCredentialsResponse;
import com.cpass.platform.client.dto.ClientProvisionResponse;
import com.cpass.platform.client.dto.ClientWebhookSecretResponse;
import com.cpass.platform.client.dto.CreateClientRequest;
import com.cpass.platform.client.dto.UpdateClientRequest;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.SecretCryptoService;
import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.common.security.UserPrincipal;
import com.cpass.platform.user.domain.UserRole;
import com.cpass.platform.user.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ClientService {

    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecretCryptoService secretCryptoService;
    private final UserService userService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ClientService(ClientRepository clientRepository,
                         PasswordEncoder passwordEncoder,
                         SecretCryptoService secretCryptoService,
                         UserService userService) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.secretCryptoService = secretCryptoService;
        this.userService = userService;
    }

    @Transactional
    public ClientProvisionResponse createClient(CreateClientRequest request) {
        assertCreateUniqueness(request.name(), request.email());
        assertQuotaConsistency(request.smsSoftQuota(), request.smsHardQuota(), request.whatsappSoftQuota(), request.whatsappHardQuota());

        ClientEntity client = new ClientEntity();
        client.setId(UUID.randomUUID());
        client.setName(request.name().trim());
        client.setEmail(request.email().trim().toLowerCase());
        client.setWebhookUrl(normalizeWebhookUrl(request.webhookUrl()));
        client.setWebhookEnabled(request.webhookEnabled() == null || request.webhookEnabled());
        client.setSmsSoftQuota(request.smsSoftQuota());
        client.setSmsHardQuota(request.smsHardQuota());
        client.setWhatsappSoftQuota(request.whatsappSoftQuota());
        client.setWhatsappHardQuota(request.whatsappHardQuota());
        client.setRateLimitPerMinute(request.rateLimitPerMinute());
        client.setEnabled(request.enabled() == null || request.enabled());
        client.setKycLevelRequired(request.kycLevelRequired() == null ? KycLevel.LEVEL_0 : request.kycLevelRequired());
        client.setScopes(resolveScopes(request.scopes(), true));
        client.setIpWhitelist(toWhitelistValue(request.ipWhitelist()));

        ClientCredentialsResponse credentials = applyNewSecret(client, generateClientPublicId());
        String webhookSecret = applyNewWebhookSecret(client);
        ClientEntity saved = clientRepository.save(client);
        userService.onboardClientOwner(saved);
        return new ClientProvisionResponse(
                saved.getId(),
                saved.getName(),
                saved.getEmail(),
                credentials.clientId(),
                credentials.clientSecret(),
                webhookSecret
        );
    }

    @Transactional(readOnly = true)
    public Page<ClientEntity> listClients(String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return clientRepository.findAll(pageable);
        }
        return clientRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(search, search, pageable);
    }

    @Transactional(readOnly = true)
    public ClientEntity getClient(UUID clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found"));
    }

    @Transactional
    public ClientEntity updateClient(UUID clientId, UpdateClientRequest request) {
        ClientEntity client = getClient(clientId);
        if (request.name() != null && !request.name().isBlank() && !request.name().equals(client.getName())) {
            clientRepository.findByName(request.name().trim())
                    .filter(existing -> !existing.getId().equals(clientId))
                    .ifPresent(existing -> {
                        throw new ApiException(HttpStatus.CONFLICT, "CLIENT_EXISTS", "Client name already exists");
                    });
            client.setName(request.name().trim());
        }
        if (request.email() != null && !request.email().isBlank() && !request.email().equalsIgnoreCase(client.getEmail())) {
            clientRepository.findByEmail(request.email().trim().toLowerCase())
                    .filter(existing -> !existing.getId().equals(clientId))
                    .ifPresent(existing -> {
                        throw new ApiException(HttpStatus.CONFLICT, "CLIENT_EMAIL_EXISTS", "Client email already exists");
                    });
            client.setEmail(request.email().trim().toLowerCase());
        }
        if (request.webhookUrl() != null) {
            client.setWebhookUrl(normalizeWebhookUrl(request.webhookUrl()));
        }
        if (request.webhookEnabled() != null) {
            client.setWebhookEnabled(request.webhookEnabled());
        }
        if (request.smsSoftQuota() != null) {
            client.setSmsSoftQuota(request.smsSoftQuota());
        }
        if (request.smsHardQuota() != null) {
            client.setSmsHardQuota(request.smsHardQuota());
        }
        if (request.whatsappSoftQuota() != null) {
            client.setWhatsappSoftQuota(request.whatsappSoftQuota());
        }
        if (request.whatsappHardQuota() != null) {
            client.setWhatsappHardQuota(request.whatsappHardQuota());
        }
        assertQuotaConsistency(client.getSmsSoftQuota(), client.getSmsHardQuota(), client.getWhatsappSoftQuota(), client.getWhatsappHardQuota());

        if (request.rateLimitPerMinute() != null) {
            client.setRateLimitPerMinute(request.rateLimitPerMinute());
        }
        if (request.enabled() != null) {
            client.setEnabled(request.enabled());
        }
        if (request.kycLevelRequired() != null) {
            client.setKycLevelRequired(request.kycLevelRequired());
        }
        if (request.scopes() != null) {
            client.setScopes(resolveScopes(request.scopes(), false));
        }
        if (request.ipWhitelist() != null) {
            client.setIpWhitelist(toWhitelistValue(request.ipWhitelist()));
        }
        return clientRepository.save(client);
    }

    @Transactional
    public ClientCredentialsResponse regenerateSecret(UUID clientId) {
        ClientEntity client = getClient(clientId);
        ClientCredentialsResponse credentials = applyNewSecret(client, client.getClientId() == null ? generateClientPublicId() : client.getClientId());
        clientRepository.save(client);
        return credentials;
    }

    @Transactional
    public ClientWebhookSecretResponse regenerateWebhookSecret(UUID clientId) {
        ClientEntity client = getClient(clientId);
        String webhookSecret = applyNewWebhookSecret(client);
        clientRepository.save(client);
        return new ClientWebhookSecretResponse(webhookSecret, Instant.now());
    }

    public void assertCanAccess(UUID clientId, UserPrincipal principal) {
        if (principal.role() == UserRole.ADMIN) {
            return;
        }
        if (principal.clientId() == null || !principal.clientId().equals(clientId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You can only access your own client");
        }
    }

    @Transactional(readOnly = true)
    public ClientEntity authenticate(String clientPublicId, String clientSecret) {
        ClientEntity client = clientRepository.findByClientId(clientPublicId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT_CREDENTIALS", "Invalid client credentials"));
        if (!client.isEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CLIENT_DISABLED", "Client is disabled");
        }
        if (client.getSecretHash() == null || !passwordEncoder.matches(clientSecret, client.getSecretHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT_CREDENTIALS", "Invalid client credentials");
        }
        return client;
    }

    public Map<String, Object> toPublicMap(ClientEntity client) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", client.getId() == null ? null : client.getId().toString());
        m.put("name", client.getName());
        m.put("email", client.getEmail());
        m.put("clientId", client.getClientId());
        m.put("webhookUrl", client.getWebhookUrl());
        m.put("webhookEnabled", client.isWebhookEnabled());
        m.put("scopes", scopeValues(client));
        m.put("createdAt", client.getCreatedAt() == null ? null : client.getCreatedAt().toString());
        return m;
    }

    public Set<String> scopeValues(ClientEntity client) {
        if (client == null || client.getScopes() == null || client.getScopes().isEmpty()) {
            return Set.of();
        }
        return client.getScopes().stream()
                .map(ClientScope::scope)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public List<String> ipWhitelist(ClientEntity client) {
        if (client.getIpWhitelist() == null || client.getIpWhitelist().isBlank()) {
            return List.of();
        }
        return Arrays.stream(client.getIpWhitelist().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private void assertCreateUniqueness(String name, String email) {
        clientRepository.findByName(name.trim()).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "CLIENT_EXISTS", "Client already exists");
        });
        clientRepository.findByEmail(email.trim().toLowerCase()).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "CLIENT_EMAIL_EXISTS", "Client email already exists");
        });
    }

    private void assertQuotaConsistency(long smsSoftQuota, long smsHardQuota, long whatsappSoftQuota, long whatsappHardQuota) {
        if (smsSoftQuota > smsHardQuota || whatsappSoftQuota > whatsappHardQuota) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA", "Soft quota must be <= hard quota");
        }
    }

    private ClientCredentialsResponse applyNewSecret(ClientEntity client, String clientPublicId) {
        String secret = "cs_" + randomToken(36);
        client.setClientId(clientPublicId);
        client.setSecretHash(passwordEncoder.encode(secret));
        return new ClientCredentialsResponse(clientPublicId, secret, Instant.now());
    }

    private String applyNewWebhookSecret(ClientEntity client) {
        String webhookSecret = "whsec_" + randomToken(36);
        client.setWebhookSecretEncrypted(secretCryptoService.encrypt(webhookSecret));
        return webhookSecret;
    }

    private Set<ClientScope> resolveScopes(Set<String> scopes, boolean applyDefaultsWhenEmpty) {
        if (scopes == null || scopes.isEmpty()) {
            return applyDefaultsWhenEmpty ? Set.of(ClientScope.values()) : Set.of();
        }
        return scopes.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(ClientScope::fromScope)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String toWhitelistValue(List<String> ipWhitelist) {
        if (ipWhitelist == null || ipWhitelist.isEmpty()) {
            return null;
        }
        return ipWhitelist.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(","));
    }

    private String normalizeWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }
        return webhookUrl.trim();
    }

    private String generateClientPublicId() {
        return "cl_" + randomToken(18);
    }

    private String randomToken(int bytes) {
        byte[] payload = new byte[bytes];
        secureRandom.nextBytes(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }
}
