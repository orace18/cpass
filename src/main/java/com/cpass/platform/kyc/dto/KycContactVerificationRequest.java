package com.cpass.platform.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record KycContactVerificationRequest(
        @NotNull UUID otpReferenceId,
        @NotBlank String otpCode
) {
}
