package com.cpass.platform.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ProviderConfigRequest(
        @NotBlank String provider,
        Map<String, Object> config,
        Boolean enabled,
        Integer priority
) {
}
