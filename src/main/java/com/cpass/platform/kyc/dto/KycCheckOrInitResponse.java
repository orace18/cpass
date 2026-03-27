package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.kyc.domain.KycReuseStatus;

import java.util.UUID;

public record KycCheckOrInitResponse(
        KycReuseStatus status,
        KycLevel kycLevel,
        UUID kycCaseId,
        String referenceId
) {
}
