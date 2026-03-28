package com.cpass.platform.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DeliveryStatusWebhookRequest(
        @NotNull UUID messageId,
        @NotBlank String providerMessageId,
        @NotBlank String status,
        String errorDescription,
        String provider,
        String timestamp
) {
}
