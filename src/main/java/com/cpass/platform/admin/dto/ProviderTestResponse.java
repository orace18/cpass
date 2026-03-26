package com.cpass.platform.admin.dto;

public record ProviderTestResponse(
        String status,
        String provider,
        String messageId,
        String error
) {
}
