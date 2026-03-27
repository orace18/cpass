package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycLevel;
import jakarta.validation.constraints.NotBlank;

public record KycCheckOrInitRequest(
        @NotBlank String userId,
        String fullName,
        String phoneNumber,
        String documentNumber,
        String faceHash,
        KycLevel requestedLevel
) {
}
