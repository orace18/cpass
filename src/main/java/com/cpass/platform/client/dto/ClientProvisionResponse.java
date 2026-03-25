package com.cpass.platform.client.dto;

import java.util.UUID;

public record ClientProvisionResponse(
        UUID clientId,
        String clientName,
        String apiKey,
        String apiSecret
) {
}
