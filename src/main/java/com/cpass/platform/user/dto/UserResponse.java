package com.cpass.platform.user.dto;

public record UserResponse(
        String id,
        String email,
        String role,
        String clientId,
        String clientName,
        boolean enabled,
        String createdAt,
        String name
) {
}
