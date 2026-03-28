package com.cpass.platform.user.dto;

import com.cpass.platform.user.domain.UserRole;
import jakarta.validation.constraints.Email;

public record UpdateUserRequest(
        @Email String email,
        UserRole role,
        String clientId,
        Boolean enabled
) {
}
