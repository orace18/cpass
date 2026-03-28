package com.cpass.platform.user.dto;

import jakarta.validation.constraints.NotBlank;

public record TwoFactorVerifyRequest(
        @NotBlank String challengeId,
        @NotBlank String code
) {
}
