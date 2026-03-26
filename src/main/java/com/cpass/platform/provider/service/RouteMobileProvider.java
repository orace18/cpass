package com.cpass.platform.provider.service;

import com.cpass.platform.messaging.domain.MessageChannel;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RouteMobileProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(RouteMobileProvider.class);
    private static final String DEFAULT_SMS_URL = "https://connect.routemobile.com/rest/send/";
    private static final String DEFAULT_WA_URL  = "https://api.rmlconnect.net/wba/whatsapp/message";

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

            if (message.channel() == MessageChannel.WHATSAPP) {
                return sendWhatsApp(message, config);
            }
            return sendSms(message, config);

        } catch (Exception e) {
            log.error("RouteMobile exception for {}: {}", message.recipient(), e.getMessage(), e);
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile exception: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // SMS
    // -------------------------------------------------------------------------

    private ProviderDispatchResult sendSms(ProviderMessage message, JsonNode config) throws Exception {
        String username = config.path("username").asText();
        String password = config.path("password").asText();
        String senderId = config.path("senderId").asText("CPASS");
        String apiUrl   = config.path("apiUrl").asText(DEFAULT_SMS_URL);
        BigDecimal cost = BigDecimal.valueOf(config.path("unitCost").asDouble(0.10));

        if (username.isBlank() || password.isBlank()) {
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile SMS credentials not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid",         username);
        body.put("password",       password);
        body.put("mobile",         message.recipient());
        body.put("senderid",       senderId);
        body.put("msg",            message.rawMessage());
        body.put("sendMethod",     "quick");
        body.put("msgType",        "text");
        body.put("duplicatecheck", "true");
        body.put("output",         "json");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile SMS HTTP error: " + response.statusCode());
        }

        JsonNode responseBody = objectMapper.readTree(response.body());
        JsonNode first = responseBody.path("response").path(0);
        String status = first.path("status").asText("");

        if ("SUCCESS".equalsIgnoreCase(status)) {
            String messageId = first.path("messageid").asText("rm_" + UUID.randomUUID());
            log.info("RouteMobile SMS sent to {} - messageId={}", message.recipient(), messageId);
            return ProviderDispatchResult.success(providerCode(), messageId, cost);
        }

        String error = first.path("error").asText(status);
        log.warn("RouteMobile SMS failed to {} - error={}", message.recipient(), error);
        return ProviderDispatchResult.failure(providerCode(), "RouteMobile SMS error: " + error);
    }

    // -------------------------------------------------------------------------
    // WhatsApp
    // -------------------------------------------------------------------------

    private ProviderDispatchResult sendWhatsApp(ProviderMessage message, JsonNode config) throws Exception {
        String apiKey   = config.path("apiKey").asText();
        String senderId = config.path("senderId").asText();
        String apiUrl   = config.path("waApiUrl").asText(DEFAULT_WA_URL);
        String language = config.path("templateLanguage").asText("en");
        BigDecimal cost = BigDecimal.valueOf(config.path("unitCost").asDouble(0.04));

        if (apiKey.isBlank()) {
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile WhatsApp apiKey not configured");
        }

        String requestBody = buildWhatsAppPayload(message, senderId, language);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 202) {
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile WhatsApp HTTP error: " + response.statusCode());
        }

        JsonNode responseBody = objectMapper.readTree(response.body());
        String messageId = responseBody.path("messages").path(0).path("id")
                .asText("rm_wa_" + UUID.randomUUID());
        log.info("RouteMobile WhatsApp sent to {} - messageId={}", message.recipient(), messageId);
        return ProviderDispatchResult.success(providerCode(), messageId, cost);
    }

    private String buildWhatsAppPayload(ProviderMessage message, String senderId, String language) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("from", senderId);
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
                ObjectNode bodyComp = objectMapper.createObjectNode();
                bodyComp.put("type", "body");
                ArrayNode parameters = objectMapper.createArrayNode();
                for (Map.Entry<String, String> entry : message.templateParams().entrySet()) {
                    ObjectNode param = objectMapper.createObjectNode();
                    param.put("type", "text");
                    param.put("text", entry.getValue());
                    parameters.add(param);
                }
                bodyComp.set("parameters", parameters);
                components.add(bodyComp);
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
}
