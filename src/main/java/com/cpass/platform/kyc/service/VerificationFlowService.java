package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.IdentityVerificationEntity;
import com.cpass.platform.kyc.domain.VerificationAnalysisEntity;
import com.cpass.platform.kyc.domain.VerificationDocumentEntity;
import com.cpass.platform.kyc.domain.VerificationStatus;
import com.cpass.platform.kyc.domain.VerificationStep;
import com.cpass.platform.kyc.dto.VerificationResponse.ScoreDetailsDto;
import com.cpass.platform.kyc.infra.IdentityVerificationRepository;
import com.cpass.platform.kyc.infra.VerificationAnalysisRepository;
import com.cpass.platform.kyc.infra.VerificationDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Asynchronous processing pipeline for identity verification.
 *
 * <p>Pipeline steps:
 * <ol>
 *   <li>Download raw document image from S3
 *   <li>Run server-side OCR — extract identity fields and portrait
 *   <li>Check document expiry
 *   <li>Download selfie from S3
 *   <li>Run server-side face comparison (portrait vs selfie)
 *   <li>Compute reliability score via weighted formula
 *   <li>Produce final decision (APPROVED / REVIEW / REJECTED)
 *   <li>Persist analysis and update verification status
 * </ol>
 *
 * <p>No client-provided values (OCR results, face hashes, scores) are ever
 * consumed here. All analysis is performed server-side on raw bytes.
 */
@Service
public class VerificationFlowService {

    private static final Logger log = LoggerFactory.getLogger(VerificationFlowService.class);

    private final IdentityVerificationRepository identityVerificationRepository;
    private final VerificationDocumentRepository verificationDocumentRepository;
    private final VerificationAnalysisRepository verificationAnalysisRepository;
    private final OcrService ocrService;
    private final FaceMatchService faceMatchService;
    private final VerificationScoringService scoringService;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    public VerificationFlowService(IdentityVerificationRepository identityVerificationRepository,
                                   VerificationDocumentRepository verificationDocumentRepository,
                                   VerificationAnalysisRepository verificationAnalysisRepository,
                                   OcrService ocrService,
                                   FaceMatchService faceMatchService,
                                   VerificationScoringService scoringService,
                                   S3Client s3Client,
                                   ObjectMapper objectMapper) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.verificationAnalysisRepository = verificationAnalysisRepository;
        this.ocrService = ocrService;
        this.faceMatchService = faceMatchService;
        this.scoringService = scoringService;
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(UUID verificationId) {
        Optional<IdentityVerificationEntity> optVerification = identityVerificationRepository.findById(verificationId);
        if (optVerification.isEmpty()) {
            log.warn("verification flow skipped — not found verificationId={}", verificationId);
            return;
        }

        IdentityVerificationEntity verification = optVerification.get();

        if (verification.getStatus() != VerificationStatus.PROCESSING) {
            log.info("verification flow skipped — status={} verificationId={}", verification.getStatus(), verificationId);
            return;
        }

        Optional<VerificationDocumentEntity> optDoc = verificationDocumentRepository.findByVerification(verification);
        if (optDoc.isEmpty()) {
            log.warn("verification flow skipped — no documents found verificationId={}", verificationId);
            return;
        }

        VerificationDocumentEntity doc = optDoc.get();

        log.info("verification flow start verificationId={} documentType={}", verificationId, verification.getDocumentType());

        // Step 1: OCR on document front
        verification.setCurrentStep(VerificationStep.DOCUMENT_ANALYSIS);
        identityVerificationRepository.save(verification);

        byte[] documentFrontBytes = downloadFromS3(doc.getBucketName(), doc.getDocumentFrontKey());
        OcrService.OcrResult ocrResult = ocrService.extract(documentFrontBytes, resolveContentType(doc.getDocumentFrontKey()));

        log.info("verification flow ocr done verificationId={} readable={} confidence={} expiryDate={}",
                verificationId, ocrResult.readable(), ocrResult.confidence(), ocrResult.expiryDate());

        boolean documentExpired = isExpired(ocrResult.expiryDate());

        // Step 2: Face match (portrait from OCR vs selfie)
        verification.setCurrentStep(VerificationStep.FACE_MATCH);
        identityVerificationRepository.save(verification);

        byte[] selfieBytes = downloadFromS3(doc.getBucketName(), doc.getSelfieKey());
        byte[] portraitBytes = ocrResult.portraitBytes();

        FaceMatchService.FaceMatchResult faceMatchResult;
        boolean faceMatchAvailable;

        if (portraitBytes != null && portraitBytes.length > 0) {
            faceMatchResult = faceMatchService.compare(portraitBytes, selfieBytes);
            faceMatchAvailable = true;
        } else {
            // No portrait extracted from document — compare full selfie with full document image as fallback
            faceMatchResult = faceMatchService.compare(documentFrontBytes, selfieBytes);
            faceMatchAvailable = faceMatchResult.faceDetectedInFirst() && faceMatchResult.faceDetectedInSecond();
            log.info("verification flow face match fallback (no portrait extracted) verificationId={} available={}",
                    verificationId, faceMatchAvailable);
        }

        log.info("verification flow face match done verificationId={} matched={} score={} available={}",
                verificationId, faceMatchResult.matched(), faceMatchResult.score(), faceMatchAvailable);

        // Step 3: Scoring and decision
        VerificationScoringService.ScoringResult scoring = scoringService.score(
                ocrResult.readable(),
                ocrResult.confidence(),
                documentExpired,
                faceMatchResult.score(),
                faceMatchAvailable
        );

        log.info("verification flow scoring done verificationId={} score={} status={} reason={}",
                verificationId, scoring.reliabilityScore(), scoring.status(), scoring.decisionReason());

        // Step 4: Persist analysis
        VerificationAnalysisEntity analysis = verificationAnalysisRepository
                .findByVerification(verification)
                .orElseGet(VerificationAnalysisEntity::new);

        analysis.setVerification(verification);
        analysis.setDocumentReadable(ocrResult.readable());
        analysis.setOcrConfidence(ocrResult.confidence());
        analysis.setExtractedFullName(ocrResult.fullName());
        analysis.setExtractedDocumentNumber(ocrResult.documentNumber());
        analysis.setExtractedBirthDate(ocrResult.dateOfBirth());
        analysis.setExtractedExpiryDate(ocrResult.expiryDate());
        analysis.setExtractedNationality(ocrResult.nationality());
        analysis.setDocumentExpired(documentExpired);
        analysis.setFaceMatchScore(faceMatchResult.score());
        analysis.setFaceMatched(faceMatchResult.matched());
        analysis.setScoreDetailsJson(serializeScoreDetails(scoring.scoreDetails()));
        verificationAnalysisRepository.save(analysis);

        // Step 5: Update verification
        verification.setCurrentStep(VerificationStep.COMPLETED);
        verification.setStatus(scoring.status());
        verification.setReliabilityScore(scoring.reliabilityScore());
        verification.setDecisionReason(scoring.decisionReason());
        identityVerificationRepository.save(verification);

        log.info("verification flow completed verificationId={} finalStatus={} score={}",
                verificationId, scoring.status(), scoring.reliabilityScore());
    }

    private byte[] downloadFromS3(String bucketName, String objectKey) {
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucketName).key(objectKey).build());
            log.info("verification flow s3 download success bucket={} key={} bytes={}",
                    bucketName, objectKey, response.asByteArray().length);
            return response.asByteArray();
        } catch (Exception ex) {
            log.error("verification flow s3 download failed bucket={} key={} error={}",
                    bucketName, objectKey, ex.getMessage());
            return new byte[0];
        }
    }

    private boolean isExpired(LocalDate expiryDate) {
        return expiryDate != null && expiryDate.isBefore(LocalDate.now());
    }

    private String resolveContentType(String objectKey) {
        if (objectKey == null) {
            return "application/octet-stream";
        }
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private String serializeScoreDetails(ScoreDetailsDto details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            log.warn("verification flow failed to serialize score details: {}", ex.getMessage());
            return null;
        }
    }
}
