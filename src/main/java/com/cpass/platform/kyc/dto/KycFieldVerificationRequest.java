package com.cpass.platform.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KycFieldVerificationRequest(
        @NotNull Double latitude,
        @NotNull Double longitude,
        @NotBlank String photoUrl
) {
}
