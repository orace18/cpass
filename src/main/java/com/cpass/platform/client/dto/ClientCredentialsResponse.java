package com.cpass.platform.client.dto;

import java.time.Instant;

public record ClientCredentialsResponse(
        String clientId,
        String clientSecret,
        Instant generatedAt
) {
}
