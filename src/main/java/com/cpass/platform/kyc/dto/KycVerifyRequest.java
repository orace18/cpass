package com.cpass.platform.kyc.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record KycVerifyRequest(
        @NotNull UUID caseId,
        @NotNull UUID otpReferenceId,
        @NotNull String otpCode
) {
}
