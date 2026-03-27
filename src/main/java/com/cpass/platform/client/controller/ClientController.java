package com.cpass.platform.client.controller;

import com.cpass.platform.billing.service.UsageService;
import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.dto.ClientCredentialsResponse;
import com.cpass.platform.client.dto.ClientProvisionResponse;
import com.cpass.platform.client.dto.CreateClientRequest;
import com.cpass.platform.client.dto.UpdateClientRequest;
import com.cpass.platform.client.service.ClientService;
import com.cpass.platform.common.security.AdminSecurityService;
import com.cpass.platform.messaging.domain.MessageChannel;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/clients")
public class ClientController {

    private final ClientService clientService;
    private final UsageService usageService;
    private final AdminSecurityService adminSecurityService;

    public ClientController(ClientService clientService,
                            UsageService usageService,
                            AdminSecurityService adminSecurityService) {
        this.clientService = clientService;
        this.usageService = usageService;
        this.adminSecurityService = adminSecurityService;
    }

    @GetMapping
    public Map<String, Object> listClients(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int pageSize,
                                           @RequestParam(defaultValue = "") String search) {
        adminSecurityService.ensureAdminToken(headerToken);
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
    public Map<String, Object> getClient(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                         @PathVariable UUID clientId) {
        adminSecurityService.ensureAdminToken(headerToken);
        return toMap(clientService.getClient(clientId));
    }

    @PostMapping
    public ClientProvisionResponse createClient(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                                @Valid @RequestBody CreateClientRequest request) {
        adminSecurityService.ensureAdminToken(headerToken);
        return clientService.createClient(request);
    }

    @PatchMapping("/{clientId}")
    public Map<String, Object> updateClient(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                            @PathVariable UUID clientId,
                                            @Valid @RequestBody UpdateClientRequest request) {
        adminSecurityService.ensureAdminToken(headerToken);
        return toMap(clientService.updateClient(clientId, request));
    }

    @PostMapping("/{clientId}/regenerate-secret")
    public ClientCredentialsResponse regenerateSecret(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                                      @PathVariable UUID clientId) {
        adminSecurityService.ensureAdminToken(headerToken);
        return clientService.regenerateSecret(clientId);
    }

    private Map<String, Object> toMap(ClientEntity client) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", client.getId().toString());
        payload.put("name", client.getName());
        payload.put("email", client.getEmail());
        payload.put("clientId", client.getClientId());
        payload.put("enabled", client.isEnabled());
        payload.put("status", client.isEnabled() ? "active" : "inactive");
        payload.put("webhookUrl", client.getWebhookUrl());
        payload.put("rateLimitPerMinute", client.getRateLimitPerMinute());
        payload.put("scopes", clientService.scopeValues(client));
        payload.put("kycLevelRequired", client.getKycLevelRequired().name());
        payload.put("ipWhitelist", clientService.ipWhitelist(client));
        payload.put("quotas", Map.of(
                "sms", client.getSmsHardQuota(),
                "whatsapp", client.getWhatsappHardQuota()
        ));
        payload.put("usage", Map.of(
                "sms", usageService.currentMonthUsage(client.getId(), MessageChannel.SMS),
                "whatsapp", usageService.currentMonthUsage(client.getId(), MessageChannel.WHATSAPP)
        ));
        payload.put("createdAt", client.getCreatedAt().toString());
        return payload;
    }
}
