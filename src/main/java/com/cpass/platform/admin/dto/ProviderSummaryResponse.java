package com.cpass.platform.admin.dto;

import java.time.Instant;

public record ProviderSummaryResponse(
        String id,
        String providerType,
        String code,
        String channel,
        int priority,
        boolean active,
        boolean configured,
        boolean configurable,
        String configJson,
        Instant createdAt,
        Instant updatedAt
) {
}
