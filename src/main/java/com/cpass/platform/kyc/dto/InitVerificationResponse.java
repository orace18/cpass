package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.VerificationStatus;

import java.util.UUID;

public record InitVerificationResponse(
        UUID verificationId,
        VerificationStatus status
) {
}
