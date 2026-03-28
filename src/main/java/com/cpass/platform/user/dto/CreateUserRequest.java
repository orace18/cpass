package com.cpass.platform.user.dto;

import com.cpass.platform.user.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserRequest(
        @NotBlank @Email String email,
        @NotNull UserRole role,
        String clientId,
        Boolean enabled
) {
}
