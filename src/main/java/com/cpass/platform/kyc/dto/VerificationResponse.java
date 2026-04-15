package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.VerificationStatus;
import com.cpass.platform.kyc.domain.VerificationStep;

import java.time.Instant;
import java.util.UUID;

public record VerificationResponse(
        UUID verificationId,
        VerificationStatus status,
        VerificationStep currentStep,
        DocumentAnalysisDto documentAnalysis,
        FaceMatchDto faceMatch,
        Integer reliabilityScore,
        ScoreDetailsDto scoreDetails,
        String decisionReason,
        Instant createdAt,
        Instant updatedAt
) {

    public record DocumentAnalysisDto(
            Boolean documentReadable,
            Boolean documentExpired,
            String documentType,
            String documentNumber,
            String fullName,
            String dateOfBirth,
            String expiryDate
    ) {
    }

    public record FaceMatchDto(
            Boolean matched,
            Integer score
    ) {
    }

    public record ScoreDetailsDto(
            Integer documentReadability,
            Integer ocrConfidence,
            Integer documentValidity,
            Integer faceMatch
    ) {
    }
}
