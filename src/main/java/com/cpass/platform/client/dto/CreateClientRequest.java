package com.cpass.platform.client.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateClientRequest(
        @NotBlank String name,
        String webhookUrl,
        @NotNull @Min(1) Long smsSoftQuota,
        @NotNull @Min(1) Long smsHardQuota,
        @NotNull @Min(1) Long whatsappSoftQuota,
        @NotNull @Min(1) Long whatsappHardQuota,
        @NotNull @Min(10) Integer rateLimitPerMinute
) {
}
