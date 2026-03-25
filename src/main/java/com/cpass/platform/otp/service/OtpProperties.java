package com.cpass.platform.otp.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        int length,
        long ttlSeconds,
        int maxSendRetries,
        int maxVerifyAttempts,
        long cooldownSeconds,
        String messageTemplate,
        String hashPepper
) {
}
