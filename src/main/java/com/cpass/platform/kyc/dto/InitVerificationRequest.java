package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.VerificationDocumentType;
import jakarta.validation.constraints.NotNull;

public record InitVerificationRequest(
        String referenceId,
        @NotNull(message = "documentType is required") VerificationDocumentType documentType,
        String firstName,
        String lastName,
        String phoneNumber
) {
}
