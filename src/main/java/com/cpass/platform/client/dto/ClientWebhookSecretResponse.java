package com.cpass.platform.client.dto;

import java.time.Instant;

public record ClientWebhookSecretResponse(
        String webhookSecret,
        Instant generatedAt
) {
}
