package com.cpass.platform.user.dto;

public record UserTokenResponse(
        String accessToken,
        long expiresIn,
        UserResponse user
) {
}
