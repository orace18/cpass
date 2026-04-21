package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycLevel;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record KycCheckOrInitRequest(
        @NotBlank String userId,
        String fullName,
        LocalDate birthDate,
        String phoneNumber,
        String documentNumber,
        String faceHash,
        KycLevel requestedLevel,
        Boolean contactVerified
) {
}
