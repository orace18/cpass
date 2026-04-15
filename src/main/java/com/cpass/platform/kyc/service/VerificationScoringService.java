package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.VerificationStatus;
import com.cpass.platform.kyc.dto.VerificationResponse.ScoreDetailsDto;
import org.springframework.stereotype.Service;

/**
 * Computes the reliability score and determines the final verification status.
 *
 * <p>Score breakdown (configurable weights, default sum = 100):
 * <ul>
 *   <li>documentReadability: 25 pts
 *   <li>ocrConfidence:       20 pts
 *   <li>documentValidity:    20 pts
 *   <li>faceMatch:           35 pts
 * </ul>
 *
 * <p>Decision thresholds (configurable):
 * <ul>
 *   <li>total >= autoApproveScoreThreshold (75) → APPROVED
 *   <li>total >= reviewScoreThreshold (50)      → REVIEW
 *   <li>total < reviewScoreThreshold            → REJECTED
 * </ul>
 */
@Service
public class VerificationScoringService {

    private final VerificationProperties properties;

    public VerificationScoringService(VerificationProperties properties) {
        this.properties = properties;
    }

    public ScoringResult score(boolean documentReadable,
                               int ocrConfidence,
                               boolean documentExpired,
                               int faceMatchScore,
                               boolean faceMatchAvailable) {

        // Raw component scores (0–100)
        int readabilityRaw = documentReadable ? 100 : (ocrConfidence < 30 ? 10 : 40);
        int ocrRaw = Math.min(100, Math.max(0, ocrConfidence));
        int validityRaw = (!documentExpired && documentReadable) ? 100 : (documentExpired ? 0 : 50);
        int faceRaw = faceMatchAvailable ? Math.min(100, Math.max(0, faceMatchScore)) : 0;

        // Weighted contributions (each weight is a percentage of the total max score)
        int readabilityScore = weighted(readabilityRaw, properties.weightDocumentReadability());
        int ocrScore = weighted(ocrRaw, properties.weightOcrConfidence());
        int validityScore = weighted(validityRaw, properties.weightDocumentValidity());
        int faceScore = weighted(faceRaw, properties.weightFaceMatch());

        int total = readabilityScore + ocrScore + validityScore + faceScore;

        ScoreDetailsDto details = new ScoreDetailsDto(readabilityScore, ocrScore, validityScore, faceScore);

        String reason;
        VerificationStatus status;

        if (!documentReadable) {
            if (ocrConfidence < 30) {
                status = VerificationStatus.REJECTED;
                reason = "DOCUMENT_UNREADABLE";
            } else {
                status = VerificationStatus.REVIEW;
                reason = "DOCUMENT_UNREADABLE";
            }
        } else if (documentExpired) {
            status = VerificationStatus.REJECTED;
            reason = "DOCUMENT_EXPIRED";
        } else if (faceMatchAvailable && faceMatchScore < properties.faceMatchThreshold()) {
            status = VerificationStatus.REVIEW;
            reason = "FACE_MISMATCH";
        } else if (total >= properties.autoApproveScoreThreshold()) {
            status = VerificationStatus.APPROVED;
            reason = "FACE_MATCH_OK_AND_DOCUMENT_VALID";
        } else if (total >= properties.reviewScoreThreshold()) {
            status = VerificationStatus.REVIEW;
            reason = "LOW_CONFIDENCE_SCORE";
        } else {
            status = VerificationStatus.REJECTED;
            reason = "INSUFFICIENT_RELIABILITY_SCORE";
        }

        return new ScoringResult(total, status, reason, details);
    }

    private int weighted(int raw, int weight) {
        return (int) Math.round(raw * weight / 100.0);
    }

    public record ScoringResult(
            int reliabilityScore,
            VerificationStatus status,
            String decisionReason,
            ScoreDetailsDto scoreDetails
    ) {
    }
}
