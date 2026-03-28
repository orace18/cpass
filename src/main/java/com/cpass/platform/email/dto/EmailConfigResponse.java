package com.cpass.platform.email.dto;

public record EmailConfigResponse(
        String id,
        String scope,
        String clientId,
        String clientName,
        String host,
        int port,
        String username,
        boolean hasPassword,
        String fromEmail,
        String fromName,
        boolean enabled
) {
}
