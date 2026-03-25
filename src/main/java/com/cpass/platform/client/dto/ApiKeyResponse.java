package com.cpass.platform.client.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        String apiKey,
        String apiSecret,
        String label,
        Instant createdAt
) {
}
