package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycLevel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record KycCaseResponse(
        UUID id,
        String referenceId,
        KycLevel level,
        KycCaseStatus status,
        Integer score,
        LocalDate documentExpiryDate,
        KycDocumentValidityStatus documentValidityStatus,
        Instant validUntil,
        Instant createdAt
) {
}
