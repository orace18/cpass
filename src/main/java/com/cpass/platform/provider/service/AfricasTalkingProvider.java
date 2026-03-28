package com.cpass.platform.provider.service;

import com.cpass.platform.common.security.SecretCryptoService;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class AfricasTalkingProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(AfricasTalkingProvider.class);
    private static final String DEFAULT_API_URL = "https://api.africastalking.com/version1/messaging";

    private final ObjectMapper objectMapper;
    private final SecretCryptoService cryptoService;
    private final HttpClient httpClient;

    public AfricasTalkingProvider(ObjectMapper objectMapper, SecretCryptoService cryptoService) {
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerCode() {
        return "AFRICA_TALKING";
    }

    @Override
    public ProviderDispatchResult send(ProviderMessage message, ProviderConfigEntity providerConfig) {
        try {
            JsonNode config = providerConfig.getConfigJson() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(providerConfig.getConfigJson());

            if (config.path("forceFailure").asBoolean(false)) {
                return ProviderDispatchResult.failure(providerCode(), "AfricasTalking forced failure");
            }

            String username = config.path("username").asText();
            String apiKey   = decryptSecret(config.path("apiKey").asText());
            String senderId = config.path("senderId").asText("");
            String apiUrl   = config.path("apiUrl").asText(DEFAULT_API_URL);
            BigDecimal cost = BigDecimal.valueOf(config.path("unitCost").asDouble(0.05));

            if (username.isBlank() || apiKey.isBlank()) {
                return ProviderDispatchResult.failure(providerCode(), "AfricasTalking credentials not configured");
            }

            String formBody = "username=" + encode(username)
                    + "&to=" + encode(message.recipient())
                    + "&message=" + encode(message.rawMessage())
                    + (senderId.isBlank() ? "" : "&from=" + encode(senderId));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("apiKey", apiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 201 && response.statusCode() != 200) {
                return ProviderDispatchResult.failure(providerCode(),
                        "AfricasTalking HTTP error: " + response.statusCode());
            }

            JsonNode body = objectMapper.readTree(response.body());
            JsonNode recipient = body.path("SMSMessageData").path("Recipients").path(0);
            int statusCode = recipient.path("statusCode").asInt(-1);
            String messageId = recipient.path("messageId").asText("");

            if (statusCode == 101 || statusCode == 102) {
                log.info("AfricasTalking SMS sent to {} - messageId={}", message.recipient(), messageId);
                return ProviderDispatchResult.success(providerCode(), messageId, cost);
            } else {
                String status = recipient.path("status").asText("Unknown error");
                log.warn("AfricasTalking SMS failed to {} - status={} code={}", message.recipient(), status, statusCode);
                return ProviderDispatchResult.failure(providerCode(), "AfricasTalking error: " + status + " (code " + statusCode + ")");
            }

        } catch (Exception e) {
            log.error("AfricasTalking exception for {}: {}", message.recipient(), e.getMessage(), e);
            return ProviderDispatchResult.failure(providerCode(), "AfricasTalking exception: " + e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String decryptSecret(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return "";
        }
        try {
            return cryptoService.decrypt(storedValue);
        } catch (Exception exception) {
            return storedValue;
        }
    }
}
