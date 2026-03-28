package com.cpass.platform.email.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EmailConfigRequest(
        @NotBlank String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        String username,
        String password,
        @NotBlank @Email String fromEmail,
        String fromName,
        Boolean enabled
) {
}
