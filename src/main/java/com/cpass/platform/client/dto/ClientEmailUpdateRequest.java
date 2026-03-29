package com.cpass.platform.client.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ClientEmailUpdateRequest(
        @NotBlank @Email String email
) {
}
