package com.cpass.platform.webhook.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.messaging.domain.MessageEntity;
import com.cpass.platform.otp.domain.OtpAuditEntity;
import com.cpass.platform.webhook.domain.WebhookDeliveryStatus;
import com.cpass.platform.webhook.domain.WebhookEventEntity;
import com.cpass.platform.webhook.dto.ClientWebhookPayload;
import com.cpass.platform.webhook.infra.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ClientWebhookEventService {

    private static final Logger log = LoggerFactory.getLogger(ClientWebhookEventService.class);

    private final ClientRepository clientRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventPublisher webhookEventPublisher;
    private final ObjectMapper objectMapper;

    public ClientWebhookEventService(ClientRepository clientRepository,
                                     WebhookEventRepository webhookEventRepository,
                                     WebhookEventPublisher webhookEventPublisher,
                                     ObjectMapper objectMapper) {
        this.clientRepository = clientRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.webhookEventPublisher = webhookEventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueueMessageEvent(MessageEntity message, String eventType) {
        if (message == null || message.getClientId() == null || message.getId() == null) {
            return;
        }

        ClientEntity client = clientRepository.findById(message.getClientId()).orElse(null);
        if (!isWebhookConfigured(client)) {
            return;
        }

        ClientWebhookPayload payload = new ClientWebhookPayload(
                null,
                eventType,
                message.getId().toString(),
                null,
                message.getStatus() == null ? null : message.getStatus().name(),
                message.getChannel() == null ? null : message.getChannel().name(),
                blankToNull(message.getProviderCode()),
                Instant.now().toString()
        );
        persistAndPublish(client, message.getId(), null, eventType, payload, dedupeKey("message", message.getId(), eventType));
    }

    @Transactional
    public void enqueueOtpEvent(OtpAuditEntity otpAudit, String eventType) {
        if (otpAudit == null || otpAudit.getClientId() == null || otpAudit.getReferenceId() == null) {
            return;
        }

        ClientEntity client = clientRepository.findById(otpAudit.getClientId()).orElse(null);
        if (!isWebhookConfigured(client)) {
            return;
        }

        ClientWebhookPayload payload = new ClientWebhookPayload(
                null,
                eventType,
                null,
                otpAudit.getReferenceId().toString(),
                otpAudit.getStatus() == null ? null : otpAudit.getStatus().name(),
                otpAudit.getChannel() == null ? null : otpAudit.getChannel().name(),
                blankToNull(otpAudit.getProviderCode()),
                Instant.now().toString()
        );
        persistAndPublish(client, null, otpAudit.getReferenceId(), eventType, payload, dedupeKey("otp", otpAudit.getReferenceId(), eventType));
    }

    private void persistAndPublish(ClientEntity client,
                                   UUID messageId,
                                   UUID otpReferenceId,
                                   String eventType,
                                   ClientWebhookPayload payload,
                                   String dedupeKey) {
        if (webhookEventRepository.findByDedupeKey(dedupeKey).isPresent()) {
            return;
        }

        UUID eventId = UUID.randomUUID();
        ClientWebhookPayload finalizedPayload = new ClientWebhookPayload(
                eventId.toString(),
                payload.eventType(),
                payload.messageId(),
                payload.referenceId(),
                payload.status(),
                payload.channel(),
                payload.provider(),
                payload.timestamp()
        );

        WebhookEventEntity event = new WebhookEventEntity();
        event.setId(eventId);
        event.setClientId(client.getId());
        event.setEventType(eventType);
        event.setDedupeKey(dedupeKey);
        event.setMessageId(messageId);
        event.setOtpReferenceId(otpReferenceId);
        event.setStatus(WebhookDeliveryStatus.PENDING);
        event.setChannel(resolveChannel(payload.channel()));
        event.setProvider(blankToNull(payload.provider()));
        event.setPayloadJson(writePayload(finalizedPayload));
        event.setWebhookUrl(client.getWebhookUrl());
        event.setAttemptCount(0);
        event.setNextAttemptAt(Instant.now());

        try {
            WebhookEventEntity saved = webhookEventRepository.save(event);
            webhookEventPublisher.publishAfterCommit(saved.getId());
        } catch (DataIntegrityViolationException exception) {
            log.debug("Skipping duplicate webhook event dedupeKey={}", dedupeKey);
        }
    }

    private boolean isWebhookConfigured(ClientEntity client) {
        return client != null
                && client.isWebhookEnabled()
                && client.getWebhookUrl() != null
                && !client.getWebhookUrl().isBlank()
                && client.getWebhookSecretEncrypted() != null
                && !client.getWebhookSecretEncrypted().isBlank();
    }

    private MessageChannel resolveChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return MessageChannel.valueOf(channel);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String writePayload(ClientWebhookPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_PAYLOAD_ERROR", "Unable to serialize webhook payload");
        }
    }

    private String dedupeKey(String aggregateType, UUID aggregateId, String eventType) {
        return aggregateType + ":" + aggregateId + ":" + eventType;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
