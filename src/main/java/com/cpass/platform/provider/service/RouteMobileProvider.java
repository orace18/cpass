package com.cpass.platform.provider.service;

import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class RouteMobileProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(RouteMobileProvider.class);
    private static final String DEFAULT_API_URL = "https://connect.routemobile.com/rest/send/";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RouteMobileProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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

            String username = config.path("username").asText();
            String password = config.path("password").asText();
            String senderId = config.path("senderId").asText("CPASS");
            String apiUrl   = config.path("apiUrl").asText(DEFAULT_API_URL);
            BigDecimal cost = BigDecimal.valueOf(config.path("unitCost").asDouble(0.10));

            if (username.isBlank() || password.isBlank()) {
                return ProviderDispatchResult.failure(providerCode(), "RouteMobile credentials not configured");
            }

            String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("userid",        username);
                put("password",      password);
                put("mobile",        message.recipient());
                put("senderid",      senderId);
                put("msg",           message.rawMessage());
                put("sendMethod",    "quick");
                put("msgType",       "text");
                put("duplicatecheck","true");
                put("output",        "json");
            }});

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ProviderDispatchResult.failure(providerCode(),
                        "RouteMobile HTTP error: " + response.statusCode());
            }

            JsonNode responseBody = objectMapper.readTree(response.body());
            JsonNode first = responseBody.path("response").path(0);
            String status = first.path("status").asText("");

            if ("SUCCESS".equalsIgnoreCase(status)) {
                String messageId = first.path("messageid").asText("rm_" + java.util.UUID.randomUUID());
                log.info("RouteMobile SMS sent to {} - messageId={}", message.recipient(), messageId);
                return ProviderDispatchResult.success(providerCode(), messageId, cost);
            } else {
                String error = first.path("error").asText(status);
                log.warn("RouteMobile SMS failed to {} - error={}", message.recipient(), error);
                return ProviderDispatchResult.failure(providerCode(), "RouteMobile error: " + error);
            }

        } catch (Exception e) {
            log.error("RouteMobile exception for {}: {}", message.recipient(), e.getMessage(), e);
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile exception: " + e.getMessage());
        }
    }
}
