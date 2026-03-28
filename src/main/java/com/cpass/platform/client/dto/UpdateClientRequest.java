package com.cpass.platform.client.dto;

import com.cpass.platform.kyc.domain.KycLevel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Set;

public record UpdateClientRequest(
        String name,
        @Email String email,
        String webhookUrl,
        Boolean webhookEnabled,
        @Min(0) Long smsSoftQuota,
        @Min(0) Long smsHardQuota,
        @Min(0) Long whatsappSoftQuota,
        @Min(0) Long whatsappHardQuota,
        @Min(1) Integer rateLimitPerMinute,
        Set<String> scopes,
        Boolean enabled,
        KycLevel kycLevelRequired,
        List<String> ipWhitelist
) {
}
