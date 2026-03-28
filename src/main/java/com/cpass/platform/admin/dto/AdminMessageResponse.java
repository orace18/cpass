package com.cpass.platform.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminMessageResponse(
        String id,
        String clientId,
        String clientName,
        String recipient,
        String channel,
        String status,
        String provider,
        Instant sentAt,
        Instant deliveredAt,
        BigDecimal cost,
        int retryCount
) {
}
