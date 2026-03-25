package com.cpass.platform.otp.dto;

public record OtpVerifyResponse(boolean verified, int attemptsRemaining) {
}
