package com.cpass.platform.provider.service;

import com.cpass.platform.common.security.SecretCryptoService;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class RouteMobileProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(RouteMobileProvider.class);

    private static final String SMS_API_URL = "https://api.rmlconnect.net/bulksms/bulksms";
    private static final String WA_API_URL  = "https://api.rmlconnect.net/wba/whatsapp/message";

    /** RouteMobile error codes (spec v1.3 — exhaustive) */
    public static final Map<String, String> RM_ERRORS = Map.ofEntries(
        Map.entry("1701", "Success — message submitted"),
        Map.entry("1702", "Invalid URL or missing parameter"),
        Map.entry("1703", "Invalid RouteMobile credentials (username/password)"),
        Map.entry("1704", "Invalid message type field"),
        Map.entry("1705", "Invalid message content"),
        Map.entry("1706", "Invalid destination number"),
        Map.entry("1707", "Invalid source / sender ID"),
        Map.entry("1709", "User validation failed"),
        Map.entry("1710", "RouteMobile internal error"),
        Map.entry("1025", "RouteMobile account has insufficient credit"),
        Map.entry("1715", "Response timeout — do not resubmit"),
        Map.entry("1032", "DND reject — number is on Do-Not-Disturb list"),
        Map.entry("1028", "Spam message rejected"),
        Map.entry("1041", "Duplicate message"),
        Map.entry("1042", "Explicit DND destination"),
        Map.entry("1033", "Message template mismatch"),
        Map.entry("69",   "RouteMobile internal error"),
        Map.entry("8",    "RouteMobile internal error"),
        Map.entry("16",   "Server IP is blacklisted by RouteMobile"),
        Map.entry("18",   "Server IP not whitelisted by RouteMobile")
    );

    /** GSM-7 Basic Character Set — chars outside this set need type=2 (Unicode). */
    private static final String GSM7_CHARS =
        "@\u00A3$\u00A5\u00E8\u00E9\u00F9\u00EC\u00F2\u00C7\n\u00D8\u00F8\r\u00C5\u00E5" +
        "\u0394_\u03A6\u0393\u039B\u03A9\u03A0\u03A8\u03A3\u0398\u039E\u001B" +
        " !\"#\u00A4%&'()*+,-./:;<=>?\u00A1ABCDEFGHIJKLMNOPQRSTUVWXYZ\u00C4\u00D6\u00D1\u00DC\u00A7" +
        "\u00BFabcdefghijklmnopqrstuvwxyz\u00E4\u00F6\u00F1\u00FC\u00E0" +
        "0123456789\n\r\u000C";

    private final ObjectMapper objectMapper;
    private final SecretCryptoService cryptoService;
    private final HttpClient httpClient;

    public RouteMobileProvider(ObjectMapper objectMapper, SecretCryptoService cryptoService) {
        this.objectMapper  = objectMapper;
        this.cryptoService = cryptoService;
        this.httpClient    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerCode() { return "ROUTE_MOBILE"; }

    @Override
    public ProviderDispatchResult send(ProviderMessage message, ProviderConfigEntity config) {
        try {
            JsonNode cfg = config.getConfigJson() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(config.getConfigJson());

            if (cfg.path("forceFailure").asBoolean(false)) {
                return ProviderDispatchResult.failure(providerCode(), "RouteMobile forced failure (test flag)");
            }

            return message.channel() == MessageChannel.WHATSAPP
                    ? sendWhatsApp(message, cfg)
                    : sendSms(message, cfg);

        } catch (Exception e) {
            log.error("RouteMobile exception recipient={} error={}", message.recipient(), e.getMessage(), e);
            return ProviderDispatchResult.failure(providerCode(), "RouteMobile exception: " + e.getMessage());
        }
    }

    // =========================================================================
    // SMS — GET https://api.rmlconnect.net/bulksms/bulksms
    // Spec v1.3: all params as URL query string, URL-UTF-8 encoded
    // Response: plain text  <code>|<destination>|<messageId>
    // =========================================================================

    private ProviderDispatchResult sendSms(ProviderMessage message, JsonNode cfg) throws Exception {
        String username = cfg.path("username").asText("").trim();
        String encPwd   = cfg.path("password").asText("").trim();
        String senderId = cfg.path("senderId").asText("CPASS").trim();
        boolean debug   = cfg.path("debug").asBoolean(false);
        BigDecimal cost = BigDecimal.valueOf(cfg.path("unitCost").asDouble(0.09));

        if (username.isEmpty() || encPwd.isEmpty()) {
            return ProviderDispatchResult.failure(providerCode(),
                "RouteMobile SMS credentials not configured — set username/password via admin panel");
        }

        String password = decryptPassword(encPwd);

        // type=0 plain GSM-7, type=2 Unicode — auto-detected
        int type = isGsm7(message.rawMessage()) ? 0 : 2;

        String url = SMS_API_URL
                + "?username="    + encode(username)
                + "&password="    + encode(password)
                + "&type="        + type
                + "&dlr=1"
                + "&destination=" + encode(message.recipient())
                + "&source="      + encode(senderId)
                + "&message="     + encode(message.rawMessage());

        log.info("RouteMobile SMS request: url={}",
            SMS_API_URL + "?username=" + encode(username) + "&password=***"
            + "&type=" + type + "&dlr=1"
            + "&destination=" + encode(message.recipient())
            + "&source=" + encode(senderId)
            + "&message=" + (debug ? encode(message.rawMessage()) : "<" + message.rawMessage().length() + "chars>"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String rawBody = response.body() == null ? "" : response.body().trim();

        log.info("RouteMobile SMS response: httpStatus={} raw={}", response.statusCode(), rawBody);

        return parseSmsResponse(rawBody, message.recipient(), cost);
    }

    /**
     * Parses RouteMobile pipe-delimited response.
     * Single:  {@code 1701|+22961000001|abc123}
     * Batch:   {@code 1701|dest1|id1,1706|dest2|,1701|dest3|id3}
     * Only 1709 is retryable. All others (incl. 1715) are permanent failures per spec.
     */
    ProviderDispatchResult parseSmsResponse(String rawBody, String recipient, BigDecimal cost) {
        if (rawBody.isBlank()) {
            log.error("RouteMobile empty response for recipient={}", recipient);
            return ProviderDispatchResult.failure(providerCode(), "Empty response from RouteMobile");
        }

        String first = rawBody.split(",")[0].trim();
        String[] parts = first.split("\\|");

        if (parts.length < 2) {
            log.error("RouteMobile unexpected response format rawBody={}", rawBody);
            return ProviderDispatchResult.failure(providerCode(),
                "Unexpected RouteMobile response: " + rawBody);
        }

        String code      = parts[0].trim();
        String messageId = parts.length >= 3 ? parts[2].trim() : null;

        if ("1701".equals(code)) {
            log.info("RouteMobile SMS success recipient={} messageId={}", recipient, messageId);
            return ProviderDispatchResult.success(providerCode(), messageId, cost);
        }

        String desc = RM_ERRORS.getOrDefault(code, "Unknown RouteMobile code: " + code);
        log.warn("RouteMobile SMS failed code={} description={} recipient={}", code, desc, recipient);

        // Only code 1709 is retryable per spec
        return ProviderDispatchResult.failure(providerCode(),
            "[" + code + "] " + desc + ("1709".equals(code) ? " — RETRIABLE" : ""));
    }

    // =========================================================================
    // WhatsApp — unchanged logic, improved logging
    // =========================================================================

    private ProviderDispatchResult sendWhatsApp(ProviderMessage message, JsonNode cfg) throws Exception {
        String apiKey   = cfg.path("apiKey").asText("").trim();
        String senderId = cfg.path("senderId").asText("").trim();
        String apiUrl   = cfg.path("waApiUrl").asText(WA_API_URL);
        String language = cfg.path("templateLanguage").asText("en");
        BigDecimal cost = BigDecimal.valueOf(cfg.path("unitCost").asDouble(0.04));

        if (apiKey.isEmpty()) {
            return ProviderDispatchResult.failure(providerCode(),
                "RouteMobile WhatsApp apiKey not configured");
        }

        String requestBody = buildWhatsAppPayload(message, senderId, language);
        log.info("RouteMobile WhatsApp request: recipient={} template={}", message.recipient(), message.templateCode());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("RouteMobile WhatsApp response: httpStatus={} body={}", response.statusCode(), response.body());

        if (response.statusCode() != 200 && response.statusCode() != 202) {
            return ProviderDispatchResult.failure(providerCode(),
                "RouteMobile WhatsApp HTTP " + response.statusCode());
        }

        JsonNode respBody = objectMapper.readTree(response.body());
        String messageId  = respBody.path("messages").path(0).path("id")
                .asText("rm_wa_" + UUID.randomUUID());
        log.info("RouteMobile WhatsApp success recipient={} messageId={}", message.recipient(), messageId);
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
                ObjectNode bodyComp  = objectMapper.createObjectNode();
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

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isGsm7(String text) {
        if (text == null || text.isEmpty()) return true;
        for (char c : text.toCharArray()) {
            if (GSM7_CHARS.indexOf(c) < 0) return false;
        }
        return true;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String decryptPassword(String stored) {
        try {
            return cryptoService.decrypt(stored);
        } catch (Exception e) {
            log.debug("RouteMobile: password not encrypted, using as-is (dev/initial setup)");
            return stored;
        }
    }

    public static String describeError(String code) {
        return RM_ERRORS.getOrDefault(code, "Unknown RouteMobile error code: " + code);
    }
}
