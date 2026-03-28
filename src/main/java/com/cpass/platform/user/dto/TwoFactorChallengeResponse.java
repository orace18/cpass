package com.cpass.platform.user.dto;

public record TwoFactorChallengeResponse(
        String challengeId,
        long expiresIn,
        String destination,
        String message
) {
}
