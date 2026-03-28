package com.cpass.platform.user.dto;

public record UserResponse(
        String id,
        String email,
        String twoFactorEmail,
        String role,
        String clientId,
        String clientName,
        boolean enabled,
        String createdAt,
        boolean twoFactorRequired,
        String name
) {
}
