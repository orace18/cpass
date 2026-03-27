package com.cpass.platform.client.dto;

import com.cpass.platform.kyc.domain.KycLevel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Set;

public record CreateClientRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String webhookUrl,
        @NotNull @Min(1) Long smsSoftQuota,
        @NotNull @Min(1) Long smsHardQuota,
        @NotNull @Min(1) Long whatsappSoftQuota,
        @NotNull @Min(1) Long whatsappHardQuota,
        @NotNull @Min(10) Integer rateLimitPerMinute,
        Set<String> scopes,
        Boolean enabled,
        KycLevel kycLevelRequired,
        List<String> ipWhitelist
) {
}
