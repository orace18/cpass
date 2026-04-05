package com.cpass.platform.provider.service;

import com.cpass.platform.common.security.SecretCryptoService;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class WhatsAppMetaProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMetaProvider.class);
    private static final String BASE_URL = "https://graph.facebook.com/v22.0";

    private final ObjectMapper objectMapper;
    private final SecretCryptoService cryptoService;
    private final HttpClient httpClient;

    public WhatsAppMetaProvider(ObjectMapper objectMapper, SecretCryptoService cryptoService) {
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerCode() {
        return "WHATSAPP_META";
    }

    @Override
    public ProviderDispatchResult send(ProviderMessage message, ProviderConfigEntity providerConfig) {
        try {
            JsonNode config = providerConfig.getConfigJson() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(providerConfig.getConfigJson());

            if (config.path("forceFailure").asBoolean(false)) {
                return ProviderDispatchResult.failure(providerCode(), "WhatsAppMeta forced failure");
            }

            String phoneNumberId = config.path("phoneNumberId").asText();
            String token         = decryptSecret(config.path("accessToken").asText(config.path("token").asText("")));
            String language      = config.path("templateLanguage").asText("en_US");
            BigDecimal cost      = BigDecimal.valueOf(config.path("unitCost").asDouble(0.05));

            if (phoneNumberId.isBlank() || token.isBlank()) {
                return ProviderDispatchResult.failure(providerCode(), "WhatsAppMeta credentials not configured");
            }

            String apiUrl = BASE_URL + "/" + phoneNumberId + "/messages";
            String requestBody = buildPayload(message, language);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                JsonNode errorBody = objectMapper.readTree(response.body());
                String error = errorBody.path("error").path("message").asText("HTTP " + response.statusCode());
                log.warn("WhatsAppMeta failed to {} - error={}", message.recipient(), error);
                return ProviderDispatchResult.failure(providerCode(), "WhatsAppMeta error: " + error);
            }

            JsonNode body = objectMapper.readTree(response.body());
            String messageId = body.path("messages").path(0).path("id").asText("wamid_" + UUID.randomUUID());
            log.info("WhatsAppMeta message sent to {} - messageId={}", message.recipient(), messageId);
            return ProviderDispatchResult.success(providerCode(), messageId, cost);

        } catch (Exception e) {
            log.error("WhatsAppMeta exception for {}: {}", message.recipient(), e.getMessage(), e);
            return ProviderDispatchResult.failure(providerCode(), "WhatsAppMeta exception: " + e.getMessage());
        }
    }

    private String buildPayload(ProviderMessage message, String language) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", message.recipient());

        if (message.templateCode() != null && !message.templateCode().isBlank()) {
            payload.put("type", "template");
            ObjectNode template = objectMapper.createObjectNode();
            template.put("name", message.templateCode());
            ObjectNode lang = objectMapper.createObjectNode();
            lang.put("code", language);
            template.set("language", lang);

            if (message.templateParams() != null && !message.templateParams().isEmpty()) {
                ArrayNode components = objectMapper.createArrayNode();
                ObjectNode bodyComponent = objectMapper.createObjectNode();
                bodyComponent.put("type", "body");
                ArrayNode parameters = objectMapper.createArrayNode();
                for (Map.Entry<String, String> entry : message.templateParams().entrySet()) {
                    ObjectNode param = objectMapper.createObjectNode();
                    param.put("type", "text");
                    param.put("text", entry.getValue());
                    parameters.add(param);
                }
                bodyComponent.set("parameters", parameters);
                components.add(bodyComponent);
                template.set("components", components);
            }
            payload.set("template", template);
        } else {
            payload.put("type", "text");
            ObjectNode text = objectMapper.createObjectNode();
            text.put("body", message.rawMessage());
            payload.set("text", text);
        }

        return objectMapper.writeValueAsString(payload);
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
