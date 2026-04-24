package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycDocumentType;

import java.util.UUID;

public record KycDocumentRequirementAdminResponse(
        UUID id,
        String country,
        KycDocumentType documentType,
        String label,
        String slot,
        String slotLabel,
        String captureMode,
        boolean active,
        int displayOrder
) {
}
