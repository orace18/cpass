package com.cpass.platform.email.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.SecretCryptoService;
import com.cpass.platform.email.domain.ClientEmailConfigEntity;
import com.cpass.platform.email.domain.GlobalEmailConfigEntity;
import com.cpass.platform.email.dto.EmailConfigRequest;
import com.cpass.platform.email.dto.EmailConfigResponse;
import com.cpass.platform.email.infra.ClientEmailConfigRepository;
import com.cpass.platform.email.infra.GlobalEmailConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmailConfigurationService {

    private final GlobalEmailConfigRepository globalEmailConfigRepository;
    private final ClientEmailConfigRepository clientEmailConfigRepository;
    private final ClientRepository clientRepository;
    private final SecretCryptoService cryptoService;
    private final EmailBootstrapProperties bootstrapProperties;

    public EmailConfigurationService(GlobalEmailConfigRepository globalEmailConfigRepository,
                                     ClientEmailConfigRepository clientEmailConfigRepository,
                                     ClientRepository clientRepository,
                                     SecretCryptoService cryptoService,
                                     EmailBootstrapProperties bootstrapProperties) {
        this.globalEmailConfigRepository = globalEmailConfigRepository;
        this.clientEmailConfigRepository = clientEmailConfigRepository;
        this.clientRepository = clientRepository;
        this.cryptoService = cryptoService;
        this.bootstrapProperties = bootstrapProperties;
    }

    @Transactional(readOnly = true)
    public EmailConfigResponse getGlobalConfig() {
        Optional<GlobalEmailConfigEntity> stored = latestGlobalConfig();
        if (stored.isPresent()) {
            return toResponse(stored.get());
        }
        if (bootstrapProperties.isConfigured()) {
            return new EmailConfigResponse(
                    "bootstrap",
                    "GLOBAL",
                    null,
                    null,
                    bootstrapProperties.host(),
                    bootstrapProperties.port(),
                    blankToNull(bootstrapProperties.username()),
                    bootstrapProperties.password() != null && !bootstrapProperties.password().isBlank(),
                    bootstrapProperties.fromEmail(),
                    blankToNull(bootstrapProperties.fromName()),
                    true
            );
        }
        return new EmailConfigResponse(null, "GLOBAL", null, null, "", 587, "", false, "", "", true);
    }

    @Transactional
    public EmailConfigResponse saveGlobalConfig(EmailConfigRequest request) {
        GlobalEmailConfigEntity entity = latestGlobalConfig().orElseGet(GlobalEmailConfigEntity::new);
        entity.setHost(request.host().trim());
        entity.setPort(request.port());
        entity.setUsername(blankToNull(request.username()));
        entity.setFromEmail(request.fromEmail().trim().toLowerCase());
        entity.setFromName(blankToNull(request.fromName()));

        String password = blankToNull(request.password());
        if (password != null) {
            entity.setPasswordEncrypted(cryptoService.encrypt(password));
        } else if (entity.getPasswordEncrypted() == null && bootstrapProperties.password() != null && !bootstrapProperties.password().isBlank()) {
            entity.setPasswordEncrypted(cryptoService.encrypt(bootstrapProperties.password()));
        }

        return toResponse(globalEmailConfigRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public EmailConfigResponse getClientConfig(UUID clientId) {
        ClientEntity client = requireClient(clientId);
        return clientEmailConfigRepository.findByClientId(clientId)
                .map(entity -> toResponse(entity, client))
                .orElseGet(() -> new EmailConfigResponse(
                        null,
                        "CLIENT",
                        client.getId().toString(),
                        client.getName(),
                        "",
                        587,
                        "",
                        false,
                        client.getEmail(),
                        "",
                        false
                ));
    }

    @Transactional
    public EmailConfigResponse saveClientConfig(UUID clientId, EmailConfigRequest request) {
        ClientEntity client = requireClient(clientId);
        ClientEmailConfigEntity entity = clientEmailConfigRepository.findByClientId(clientId)
                .orElseGet(ClientEmailConfigEntity::new);

        entity.setClientId(clientId);
        entity.setHost(request.host().trim());
        entity.setPort(request.port());
        entity.setUsername(blankToNull(request.username()));
        entity.setFromEmail(request.fromEmail().trim().toLowerCase());
        entity.setFromName(blankToNull(request.fromName()));
        entity.setEnabled(request.enabled() == null || request.enabled());

        String password = blankToNull(request.password());
        if (password != null) {
            entity.setPasswordEncrypted(cryptoService.encrypt(password));
        }

        return toResponse(clientEmailConfigRepository.save(entity), client);
    }

    @Transactional(readOnly = true)
    public Optional<SmtpSettings> resolveForClient(UUID clientId) {
        if (clientId != null) {
            Optional<SmtpSettings> clientConfig = clientEmailConfigRepository.findByClientId(clientId)
                    .filter(ClientEmailConfigEntity::isEnabled)
                    .map(entity -> new SmtpSettings(
                            entity.getHost(),
                            entity.getPort(),
                            blankToNull(entity.getUsername()),
                            decrypt(entity.getPasswordEncrypted()),
                            entity.getFromEmail(),
                            blankToNull(entity.getFromName()),
                            "CLIENT",
                            clientId.toString()
                    ));
            if (clientConfig.isPresent()) {
                return clientConfig;
            }
        }

        return latestGlobalConfig().map(entity -> new SmtpSettings(
                entity.getHost(),
                entity.getPort(),
                blankToNull(entity.getUsername()),
                decrypt(entity.getPasswordEncrypted()),
                entity.getFromEmail(),
                blankToNull(entity.getFromName()),
                "GLOBAL",
                null
        ));
    }

    private Optional<GlobalEmailConfigEntity> latestGlobalConfig() {
        return globalEmailConfigRepository.findAll().stream()
                .max(Comparator.comparing(GlobalEmailConfigEntity::getUpdatedAt));
    }

    private EmailConfigResponse toResponse(GlobalEmailConfigEntity entity) {
        return new EmailConfigResponse(
                entity.getId().toString(),
                "GLOBAL",
                null,
                null,
                entity.getHost(),
                entity.getPort(),
                blankToNull(entity.getUsername()),
                entity.getPasswordEncrypted() != null && !entity.getPasswordEncrypted().isBlank(),
                entity.getFromEmail(),
                blankToNull(entity.getFromName()),
                true
        );
    }

    private EmailConfigResponse toResponse(ClientEmailConfigEntity entity, ClientEntity client) {
        return new EmailConfigResponse(
                entity.getId().toString(),
                "CLIENT",
                client.getId().toString(),
                client.getName(),
                entity.getHost(),
                entity.getPort(),
                blankToNull(entity.getUsername()),
                entity.getPasswordEncrypted() != null && !entity.getPasswordEncrypted().isBlank(),
                entity.getFromEmail(),
                blankToNull(entity.getFromName()),
                entity.isEnabled()
        );
    }

    private ClientEntity requireClient(UUID clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found"));
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return cryptoService.decrypt(encrypted);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record SmtpSettings(
            String host,
            int port,
            String username,
            String password,
            String fromEmail,
            String fromName,
            String scope,
            String clientId
    ) {
    }
}
