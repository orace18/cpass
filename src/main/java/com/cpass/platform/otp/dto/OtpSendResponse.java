package com.cpass.platform.otp.dto;

import java.time.Instant;
import java.util.UUID;

public record OtpSendResponse(UUID referenceId, Instant expiresAt) {
}
