package com.cpass.platform.user.dto;

import jakarta.validation.constraints.Email;

public record UpdateProfileRequest(
        @Email String email,
        @Email String twoFactorEmail
) {
}
