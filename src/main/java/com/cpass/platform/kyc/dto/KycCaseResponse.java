package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.kyc.domain.KycNextAction;
import com.cpass.platform.kyc.domain.KycReasonCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record KycCaseResponse(
        UUID id,
        String referenceId,
        KycLevel level,
        KycCaseStatus status,
        KycCaseStatus caseStatus,
        Integer score,
        LocalDate documentExpiryDate,
        KycDocumentValidityStatus documentValidityStatus,
        Instant validUntil,
        Instant createdAt,
        String statusReason,
        KycReasonCode reasonCode,
        KycNextAction nextAction,
        String recommendation,
        Map<String, Object> extractedFields,
        Integer confidenceScore,
        String requestId,
        UUID clientId,
        UUID caseId,
        String productStatus
) {
}
