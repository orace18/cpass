package com.cpass.platform.client.dto;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;

public record ClientWebhookUpdateRequest(
        @NotNull @URL String webhookUrl,
        Boolean webhookEnabled
) {
}
