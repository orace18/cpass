package com.cpass.platform.admin.controller;

import com.cpass.platform.admin.dto.ProviderTestRequest;
import com.cpass.platform.admin.dto.ProviderTestResponse;
import com.cpass.platform.admin.dto.RouteMobileConfigRequest;
import com.cpass.platform.admin.dto.RouteMobileConfigResponse;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.SecretCryptoService;
import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.cpass.platform.provider.infra.ProviderConfigRepository;
import com.cpass.platform.provider.service.ProviderDispatchResult;
import com.cpass.platform.provider.service.ProviderRoutingService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static final String RM_CODE    = "ROUTE_MOBILE";
    private static final MessageChannel RM_CHANNEL = MessageChannel.SMS;

    private final ProviderRoutingService   providerRoutingService;
    private final ProviderConfigRepository providerConfigRepository;
    private final SecretCryptoService      cryptoService;
    private final ObjectMapper             objectMapper;
    private final String                   adminToken;

    public AdminController(ProviderRoutingService providerRoutingService,
                           ProviderConfigRepository providerConfigRepository,
                           SecretCryptoService cryptoService,
                           ObjectMapper objectMapper,
                           @Value("${app.admin-token}") String adminToken) {
        this.providerRoutingService   = providerRoutingService;
        this.providerConfigRepository = providerConfigRepository;
        this.cryptoService            = cryptoService;
        this.objectMapper             = objectMapper;
        this.adminToken               = adminToken;
    }

    // -------------------------------------------------------------------------
    // Generic provider test (any provider/channel)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // RouteMobile SMS config — GET (read current) and POST (save/update)
    // -------------------------------------------------------------------------

    @GetMapping("/providers/routemobile/config")
    public RouteMobileConfigResponse getRouteMobileConfig(
            @RequestHeader("X-ADMIN-TOKEN") String headerToken) throws Exception {
        ensureAdminToken(headerToken);
        ProviderConfigEntity entity = findRouteMobileEntity();
        return toConfigResponse(entity);
    }

    @PostMapping("/providers/routemobile/config")
    public RouteMobileConfigResponse saveRouteMobileConfig(
            @RequestHeader("X-ADMIN-TOKEN") String headerToken,
            @Valid @RequestBody RouteMobileConfigRequest request) throws Exception {
        ensureAdminToken(headerToken);

        ProviderConfigEntity entity = findRouteMobileEntity();

        // Merge existing JSON with new values
        ObjectNode cfg = entity.getConfigJson() == null
                ? objectMapper.createObjectNode()
                : (ObjectNode) objectMapper.readTree(entity.getConfigJson());

        cfg.put("username", request.username().trim());

        // Only update password when a new one is provided
        if (request.password() != null && !request.password().isBlank()) {
            cfg.put("password", cryptoService.encrypt(request.password().trim()));
        }

        if (request.senderId() != null && !request.senderId().isBlank()) {
            cfg.put("senderId", request.senderId().trim());
        }
        if (request.unitCost() != null) {
            cfg.put("unitCost", request.unitCost());
        }
        if (request.debug() != null) {
            cfg.put("debug", request.debug());
        }

        entity.setConfigJson(objectMapper.writeValueAsString(cfg));
        entity = providerConfigRepository.save(entity);

        return toConfigResponse(entity);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProviderConfigEntity findRouteMobileEntity() {
        return providerConfigRepository.findByCodeAndChannel(RM_CODE, RM_CHANNEL)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "PROVIDER_NOT_FOUND", "No ROUTE_MOBILE SMS provider row found in database"));
    }

    private RouteMobileConfigResponse toConfigResponse(ProviderConfigEntity entity) throws Exception {
        ObjectNode cfg = entity.getConfigJson() == null
                ? objectMapper.createObjectNode()
                : (ObjectNode) objectMapper.readTree(entity.getConfigJson());

        return new RouteMobileConfigResponse(
                entity.getId().toString(),
                entity.getCode(),
                entity.getChannel().name(),
                entity.isActive(),
                cfg.path("username").asText(""),
                !cfg.path("password").asText("").isBlank(),
                cfg.path("senderId").asText("CPASS"),
                cfg.path("unitCost").asDouble(0.09),
                cfg.path("debug").asBoolean(false)
        );
    }

    private void ensureAdminToken(String headerToken) {
        if (headerToken == null || !headerToken.equals(adminToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Invalid admin token");
        }
    }
}
