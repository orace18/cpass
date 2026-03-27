package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycCaseStatus;
import jakarta.validation.constraints.NotNull;

public record AdminKycReviewRequest(
        @NotNull KycCaseStatus status,
        String reason
) {
}
