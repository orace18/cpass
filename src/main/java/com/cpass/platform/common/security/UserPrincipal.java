package com.cpass.platform.common.security;

import com.cpass.platform.user.domain.UserRole;

import java.util.UUID;

public record UserPrincipal(
        UUID userId,
        String email,
        UserRole role,
        UUID clientId
) {
}
