package com.cpass.platform.client.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateApiKeyRequest(@NotBlank String label) {
}
