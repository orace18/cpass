package com.cpass.platform.client.controller;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.dto.ApiKeyResponse;
import com.cpass.platform.client.dto.ClientProvisionResponse;
import com.cpass.platform.client.dto.CreateClientRequest;
import com.cpass.platform.client.dto.GenerateApiKeyRequest;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.client.service.ClientService;
import com.cpass.platform.common.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final ClientService      clientService;
    private final ClientRepository   clientRepository;
    private final String             adminToken;

    public ClientController(ClientService clientService,
                            ClientRepository clientRepository,
                            @Value("${app.admin-token}") String adminToken) {
        this.clientService    = clientService;
        this.clientRepository = clientRepository;
        this.adminToken       = adminToken;
    }

    // ── List clients (admin) ─────────────────────────────────────────────────
    @GetMapping
    public Map<String, Object> listClients(
            @RequestHeader("X-ADMIN-TOKEN") String headerToken,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "")   String search) {
        ensureAdminToken(headerToken);

        PageRequest pageable = PageRequest.of(
                Math.max(0, page - 1), pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<ClientEntity> result = search.isBlank()
                ? clientRepository.findAll(pageable)
                : clientRepository.findByNameContainingIgnoreCase(search, pageable);

        List<Map<String, Object>> data = result.getContent().stream()
                .map(this::toMap)
                .toList();

        return Map.of(
                "data",     data,
                "total",    result.getTotalElements(),
                "page",     page,
                "pageSize", pageSize
        );
    }

    // ── Get single client (admin) ─────────────────────────────────────────────
    @GetMapping("/{clientId}")
    public Map<String, Object> getClient(
            @RequestHeader("X-ADMIN-TOKEN") String headerToken,
            @PathVariable UUID clientId) {
        ensureAdminToken(headerToken);
        return toMap(clientService.getClient(clientId));
    }

    // ── Create client (admin) ─────────────────────────────────────────────────
    @PostMapping
    public ClientProvisionResponse createClient(
            @RequestHeader("X-ADMIN-TOKEN") String headerToken,
            @Valid @RequestBody CreateClientRequest request) {
        ensureAdminToken(headerToken);
        return clientService.createClient(request);
    }

    // ── Generate API key (admin) ──────────────────────────────────────────────
    @PostMapping("/{clientId}/api-keys")
    public ApiKeyResponse generateApiKey(
            @RequestHeader("X-ADMIN-TOKEN") String headerToken,
            @PathVariable UUID clientId,
            @Valid @RequestBody GenerateApiKeyRequest request) {
        ensureAdminToken(headerToken);
        return clientService.rotateApiKey(clientId, request.label());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(ClientEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          c.getId().toString());
        m.put("name",        c.getName());
        m.put("webhookUrl",  c.getWebhookUrl());
        m.put("active",      c.isActive());
        m.put("quotas",      Map.of("sms", c.getSmsHardQuota(), "whatsapp", c.getWhatsappHardQuota()));
        m.put("usage",       Map.of("sms", 0, "whatsapp", 0));
        m.put("createdAt",   c.getCreatedAt().toString());
        return m;
    }

    private void ensureAdminToken(String headerToken) {
        if (headerToken == null || !headerToken.equals(adminToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Invalid admin token");
        }
    }
}
