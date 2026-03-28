package com.cpass.platform.webhook.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.security.HmacSigner;
import com.cpass.platform.common.security.SecretCryptoService;
import com.cpass.platform.webhook.domain.WebhookDeliveryStatus;
import com.cpass.platform.webhook.domain.WebhookEventEntity;
import com.cpass.platform.webhook.infra.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WebhookDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);
    private static final List<Duration> BACKOFFS = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1)
    );
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 5;

    private final WebhookEventRepository webhookEventRepository;
    private final ClientRepository clientRepository;
    private final SecretCryptoService secretCryptoService;
    private final HttpClient httpClient;

    public WebhookDispatchService(WebhookEventRepository webhookEventRepository,
                                  ClientRepository clientRepository,
                                  SecretCryptoService secretCryptoService) {
        this.webhookEventRepository = webhookEventRepository;
        this.clientRepository = clientRepository;
        this.secretCryptoService = secretCryptoService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void process(UUID eventId) {
        WebhookEventEntity event = claimForDispatch(eventId);
        if (event == null) {
            return;
        }

        ClientEntity client = clientRepository.findById(event.getClientId()).orElse(null);
        if (client == null || !client.isWebhookEnabled()) {
            markFailed(event.getId(), event.getAttemptCount(), null, "Webhook client configuration is unavailable");
            return;
        }

        if (client.getWebhookSecretEncrypted() == null || client.getWebhookSecretEncrypted().isBlank()) {
            markFailed(event.getId(), event.getAttemptCount(), null, "Webhook secret is not configured");
            return;
        }

        String secret = decryptSecret(client.getWebhookSecretEncrypted());
        if (secret == null || secret.isBlank()) {
            markFailed(event.getId(), event.getAttemptCount(), null, "Webhook secret is invalid");
            return;
        }

        Instant now = Instant.now();
        String timestamp = String.valueOf(now.getEpochSecond());
        String signature = HmacSigner.hmacSha256Hex(secret, timestamp + "." + event.getPayloadJson());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(event.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(event.getPayloadJson()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                markDelivered(event.getId(), response.statusCode(), now);
                return;
            }
            markRetry(event.getId(), event.getAttemptCount(), response.statusCode(),
                    "Webhook endpoint returned HTTP " + response.statusCode(), now);
        } catch (Exception exception) {
            log.warn("Webhook delivery failed eventId={} url={} error={}", event.getId(), event.getWebhookUrl(), exception.getMessage());
            markRetry(event.getId(), event.getAttemptCount(), null, exception.getMessage(), now);
        }
    }

    @Transactional
    protected WebhookEventEntity claimForDispatch(UUID eventId) {
        Instant now = Instant.now();
        int claimed = webhookEventRepository.claimForDispatch(
                eventId,
                WebhookDeliveryStatus.PROCESSING,
                List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRY),
                now,
                WebhookDeliveryStatus.PROCESSING,
                now.minus(PROCESSING_TIMEOUT)
        );
        if (claimed == 0) {
            return null;
        }
        return webhookEventRepository.findById(eventId).orElse(null);
    }

    @Transactional
    protected void markDelivered(UUID eventId, Integer httpStatus, Instant deliveredAt) {
        webhookEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(WebhookDeliveryStatus.DELIVERED);
            event.setLastHttpStatus(httpStatus);
            event.setLastError(null);
            event.setDeliveredAt(deliveredAt);
            event.setNextAttemptAt(deliveredAt);
            webhookEventRepository.save(event);
        });
    }

    @Transactional
    protected void markRetry(UUID eventId, int attemptCount, Integer httpStatus, String error, Instant attemptedAt) {
        webhookEventRepository.findById(eventId).ifPresent(event -> {
            if (attemptCount >= MAX_ATTEMPTS) {
                event.setStatus(WebhookDeliveryStatus.FAILED);
                event.setNextAttemptAt(attemptedAt);
            } else {
                event.setStatus(WebhookDeliveryStatus.RETRY);
                event.setNextAttemptAt(attemptedAt.plus(resolveBackoff(attemptCount)));
            }
            event.setLastHttpStatus(httpStatus);
            event.setLastError(truncate(error));
            webhookEventRepository.save(event);
        });
    }

    @Transactional
    protected void markFailed(UUID eventId, int attemptCount, Integer httpStatus, String error) {
        markRetry(eventId, Math.max(MAX_ATTEMPTS, attemptCount), httpStatus, error, Instant.now());
    }

    private Duration resolveBackoff(int attemptCount) {
        if (attemptCount <= 1) {
            return BACKOFFS.get(0);
        }
        if (attemptCount == 2) {
            return BACKOFFS.get(1);
        }
        if (attemptCount == 3) {
            return BACKOFFS.get(2);
        }
        return BACKOFFS.get(3);
    }

    private String decryptSecret(String encryptedSecret) {
        try {
            return secretCryptoService.decrypt(encryptedSecret);
        } catch (Exception exception) {
            return null;
        }
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
