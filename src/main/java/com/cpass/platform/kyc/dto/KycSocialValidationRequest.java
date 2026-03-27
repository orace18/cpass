package com.cpass.platform.kyc.dto;

import jakarta.validation.constraints.NotBlank;

public record KycSocialValidationRequest(
        @NotBlank String guarantorName,
        @NotBlank String guarantorPhone,
        String notes
) {
}
