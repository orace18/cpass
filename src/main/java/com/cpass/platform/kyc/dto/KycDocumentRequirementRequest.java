package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KycDocumentRequirementRequest(
        @NotBlank String country,
        @NotNull KycDocumentType documentType,
        @NotBlank String label,
        @NotBlank String slot,
        @NotBlank String slotLabel,
        @NotBlank String captureMode,
        Boolean active,
        Integer displayOrder
) {
}
