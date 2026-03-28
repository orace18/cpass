package com.cpass.platform.email.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record TestEmailRequest(
        @NotBlank @Email String to,
        String clientId
) {
}
