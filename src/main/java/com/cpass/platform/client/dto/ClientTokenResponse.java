package com.cpass.platform.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClientTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn
) {
}
