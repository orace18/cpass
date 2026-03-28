package com.cpass.platform.client.controller;

import com.cpass.platform.billing.service.UsageService;
import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.dto.ClientCredentialsResponse;
import com.cpass.platform.client.dto.ClientProvisionResponse;
import com.cpass.platform.client.dto.ClientWebhookSecretResponse;
import com.cpass.platform.client.dto.CreateClientRequest;
import com.cpass.platform.client.dto.UpdateClientRequest;
import com.cpass.platform.client.service.ClientService;
import com.cpass.platform.common.security.UserContextHolder;
import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.messaging.domain.MessageChannel;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/clients")
public class ClientController {

    private static final Logger log = LoggerFactory.getLogger(ClientController.class);

    private final ClientService clientService;
    private final UsageService usageService;

    public ClientController(ClientService clientService,
                            UsageService usageService) {
        this.clientService = clientService;
        this.usageService = usageService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> listClients(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int pageSize,
                                           @RequestParam(defaultValue = "") String search) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ClientEntity> result = clientService.listClients(search, pageable);

        List<Map<String, Object>> data = result.getContent().stream()
                .map(this::toMap)
                .toList();

        return Map.of(
                "data", data,
                "total", result.getTotalElements(),
                "page", page,
                "pageSize", pageSize
        );
    }

    @GetMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('ADMIN','CLIENT')")
    public Map<String, Object> getClient(@PathVariable UUID clientId) {
        clientService.assertCanAccess(clientId, UserContextHolder.requireUser());
        return toMap(clientService.getClient(clientId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ClientProvisionResponse createClient(@Valid @RequestBody CreateClientRequest request) {
        return clientService.createClient(request);
    }

    @PatchMapping("/{clientId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateClient(@PathVariable UUID clientId,
                                            @Valid @RequestBody UpdateClientRequest request) {
        return toMap(clientService.updateClient(clientId, request));
    }

    @PostMapping("/{clientId}/regenerate-secret")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientCredentialsResponse regenerateSecret(@PathVariable UUID clientId) {
        return clientService.regenerateSecret(clientId);
    }

    @PostMapping("/{clientId}/regenerate-webhook-secret")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientWebhookSecretResponse regenerateWebhookSecret(@PathVariable UUID clientId) {
        return clientService.regenerateWebhookSecret(clientId);
    }

    private Map<String, Object> toMap(ClientEntity client) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", client.getId() == null ? null : client.getId().toString());
        payload.put("name", client.getName());
        payload.put("email", client.getEmail());
        payload.put("clientId", client.getClientId());
        payload.put("enabled", client.isEnabled());
        payload.put("status", client.isEnabled() ? "active" : "inactive");
        payload.put("webhookUrl", client.getWebhookUrl());
        payload.put("webhookEnabled", client.isWebhookEnabled());
        payload.put("webhookSecretConfigured", client.getWebhookSecretEncrypted() != null && !client.getWebhookSecretEncrypted().isBlank());
        payload.put("rateLimitPerMinute", client.getRateLimitPerMinute());
        payload.put("scopes", clientService.scopeValues(client));
        payload.put("kycLevelRequired", client.getKycLevelRequired() == null
                ? KycLevel.LEVEL_0.name()
                : client.getKycLevelRequired().name());
        payload.put("ipWhitelist", clientService.ipWhitelist(client));
        payload.put("quotas", Map.of(
                "sms", client.getSmsHardQuota(),
                "whatsapp", client.getWhatsappHardQuota()
        ));
        payload.put("usage", Map.of(
                "sms", safeCurrentMonthUsage(client.getId(), MessageChannel.SMS),
                "whatsapp", safeCurrentMonthUsage(client.getId(), MessageChannel.WHATSAPP)
        ));
        payload.put("createdAt", toIsoString(client.getCreatedAt(), client.getUpdatedAt()));
        return payload;
    }

    private long safeCurrentMonthUsage(UUID clientId, MessageChannel channel) {
        if (clientId == null) {
            return 0L;
        }
        try {
            return usageService.currentMonthUsage(clientId, channel);
        } catch (RuntimeException exception) {
            log.warn("Unable to resolve usage for clientId={} channel={}: {}", clientId, channel, exception.getMessage());
            return 0L;
        }
    }

    private String toIsoString(Instant primary, Instant fallback) {
        Instant resolved = primary != null ? primary : fallback;
        return resolved == null ? null : resolved.toString();
    }
}
