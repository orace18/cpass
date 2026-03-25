package com.cpass.platform.provider.service;

import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class RouteMobileProvider implements MessagingProvider {

    private final ObjectMapper objectMapper;

    public RouteMobileProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return "ROUTE_MOBILE";
    }

    @Override
    public ProviderDispatchResult send(ProviderMessage message, ProviderConfigEntity providerConfig) {
        try {
            JsonNode config = providerConfig.getConfigJson() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(providerConfig.getConfigJson());
            if (config.path("forceFailure").asBoolean(false)) {
                return ProviderDispatchResult.failure(providerCode(), "RouteMobile forced failure");
            }
            BigDecimal cost = BigDecimal.valueOf(config.path("unitCost").asDouble(0.10));
            return ProviderDispatchResult.success(providerCode(), "rm_" + UUID.randomUUID(), cost);
        } catch (Exception exception) {
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile error: " + exception.getMessage());
        }
    }
}
