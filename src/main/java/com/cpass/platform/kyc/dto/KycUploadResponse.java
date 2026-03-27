package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;

import java.util.UUID;

public record KycUploadResponse(
        UUID documentId,
        KycDocumentStatus status,
        String objectKey,
        KycDocumentValidityStatus validityStatus
) {
}
