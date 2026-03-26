package com.cpass.platform.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ProviderTestRequest(
        @NotBlank String provider,
        @NotBlank String channel,
        @NotBlank String to,
        String message,
        String templateCode,
        Map<String, String> templateParams
) {
}
