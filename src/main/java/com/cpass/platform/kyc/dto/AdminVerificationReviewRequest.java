package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.VerificationStatus;
import jakarta.validation.constraints.NotNull;

public record AdminVerificationReviewRequest(
        @NotNull(message = "status is required") VerificationStatus status,
        String reason
) {
}
