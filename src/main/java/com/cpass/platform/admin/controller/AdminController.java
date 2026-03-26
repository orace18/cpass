package com.cpass.platform.admin.controller;

import com.cpass.platform.admin.dto.ProviderTestRequest;
import com.cpass.platform.admin.dto.ProviderTestResponse;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.cpass.platform.provider.service.ProviderDispatchResult;
import com.cpass.platform.provider.service.ProviderRoutingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ProviderRoutingService providerRoutingService;
    private final String adminToken;

    public AdminController(ProviderRoutingService providerRoutingService,
                           @Value("${app.admin-token}") String adminToken) {
        this.providerRoutingService = providerRoutingService;
        this.adminToken = adminToken;
    }

    @PostMapping("/providers/test")
    public ProviderTestResponse testProvider(@RequestHeader("X-ADMIN-TOKEN") String headerToken,
                                             @Valid @RequestBody ProviderTestRequest request) {
        ensureAdminToken(headerToken);

        MessageChannel channel = MessageChannel.valueOf(request.channel().toUpperCase());

        ProviderMessage message = new ProviderMessage(
                UUID.randomUUID(),
                channel,
                request.to(),
                request.message(),
                request.templateCode(),
                request.templateParams() != null ? request.templateParams() : Map.of()
        );

        ProviderDispatchResult result = providerRoutingService.directDispatch(
                request.provider().toUpperCase(), channel, message);

        return new ProviderTestResponse(
                result.success() ? "SUCCESS" : "FAILED",
                result.providerCode(),
                result.providerMessageId(),
                result.error()
        );
    }

    private void ensureAdminToken(String headerToken) {
        if (headerToken == null || !headerToken.equals(adminToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Invalid admin token");
        }
    }
}
