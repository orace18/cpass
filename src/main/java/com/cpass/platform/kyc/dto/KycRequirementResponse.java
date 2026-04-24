package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycDocumentType;

import java.util.List;

public record KycRequirementResponse(
        String country,
        List<AcceptedDocument> acceptedDocuments
) {
    public record AcceptedDocument(
            KycDocumentType documentType,
            String label,
            List<RequiredSlot> requiredSlots
    ) {
    }

    public record RequiredSlot(
            String slot,
            String slotLabel,
            String captureMode
    ) {
    }
}
