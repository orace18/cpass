package com.cpass.platform.client.dto;

import java.util.UUID;

public record ClientProvisionResponse(
        UUID id,
        String name,
        String email,
        String clientId,
        String clientSecret,
        String webhookSecret
) {
}
