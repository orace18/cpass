package com.cpass.platform.client.controller;

import com.cpass.platform.client.dto.ApiKeyResponse;
import com.cpass.platform.client.dto.ClientProvisionResponse;
import com.cpass.platform.client.dto.CreateClientRequest;
import com.cpass.platform.client.dto.GenerateApiKeyRequest;
import com.cpass.platform.client.service.ClientService;
import com.cpass.platform.common.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/clients")
public class ClientController {

    private final ClientService clientService;
    private final String adminToken;

    public ClientController(ClientService clientService,
                            @Value("${app.admin-token}") String adminToken) {
        this.clientService = clientService;
        this.adminToken = adminToken;
    }

    @PostMapping
    public ClientProvisionResponse createClient(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                                @Valid @RequestBody CreateClientRequest request) {
        ensureAdminToken(headerToken);
        return clientService.createClient(request);
    }

    @PostMapping("/{clientId}/api-keys")
    public ApiKeyResponse generateApiKey(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                         @PathVariable UUID clientId,
                                         @Valid @RequestBody GenerateApiKeyRequest request) {
        ensureAdminToken(headerToken);
        return clientService.rotateApiKey(clientId, request.label());
    }

    private void ensureAdminToken(String headerToken) {
        if (headerToken == null || !headerToken.equals(adminToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Invalid admin token");
        }
    }
}
