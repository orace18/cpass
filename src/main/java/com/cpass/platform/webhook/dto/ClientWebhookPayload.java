package com.cpass.platform.webhook.dto;

public record ClientWebhookPayload(
        String eventId,
        String eventType,
        String messageId,
        String referenceId,
        String status,
        String channel,
        String provider,
        String timestamp
) {
}
