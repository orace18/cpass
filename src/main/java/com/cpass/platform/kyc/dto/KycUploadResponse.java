package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycNextAction;
import com.cpass.platform.kyc.domain.KycReasonCode;

import java.util.Map;
import java.util.UUID;

public record KycUploadResponse(
        UUID documentId,
        UUID caseId,
        KycDocumentStatus status,
        String objectKey,
        KycDocumentValidityStatus validityStatus,
        KycCaseStatus caseStatus,
        String statusReason,
        KycReasonCode reasonCode,
        KycNextAction nextAction,
        String recommendation,
        Map<String, Object> extractedFields,
        Integer confidenceScore,
        String requestId,
        UUID clientId,
        String productStatus
) {
}
