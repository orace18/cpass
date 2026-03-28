package com.cpass.platform.otp.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record OtpVerifyRequest(
        @NotNull UUID referenceId,
        @NotBlank @JsonAlias("code") String otpCode
) {
}
