package com.cpass.platform.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ClientTokenRequest(
        @JsonProperty("client_id") @NotBlank String clientId,
        @JsonProperty("client_secret") @NotBlank String clientSecret
) {
}
