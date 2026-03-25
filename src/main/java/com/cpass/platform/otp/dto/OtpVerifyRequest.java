package com.cpass.platform.otp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OtpVerifyRequest(
        @NotNull UUID referenceId,
        @NotBlank String otpCode
) {
}
