package com.cpass.platform.otp.service;

import com.cpass.platform.messaging.domain.MessageChannel;

import java.time.Instant;

public record OtpCacheRecord(
        String destination,
        MessageChannel channel,
        String hash,
        String salt,
        int attemptsRemaining,
        Instant expiresAt
) {
}
