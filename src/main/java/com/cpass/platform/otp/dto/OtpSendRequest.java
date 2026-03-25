package com.cpass.platform.otp.dto;

import jakarta.validation.constraints.NotBlank;

public record OtpSendRequest(@NotBlank String destination) {
}
