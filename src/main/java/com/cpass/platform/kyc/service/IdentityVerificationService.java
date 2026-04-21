package com.cpass.platform.kyc.service;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.kyc.domain.IdentityVerificationEntity;
import com.cpass.platform.kyc.domain.VerificationAnalysisEntity;
import com.cpass.platform.kyc.domain.VerificationDocumentEntity;
import com.cpass.platform.kyc.domain.VerificationStatus;
import com.cpass.platform.kyc.domain.VerificationStep;
import com.cpass.platform.kyc.dto.AdminVerificationReviewRequest;
import com.cpass.platform.kyc.dto.InitVerificationRequest;
import com.cpass.platform.kyc.dto.InitVerificationResponse;
import com.cpass.platform.kyc.dto.UploadVerificationResponse;
import com.cpass.platform.kyc.dto.VerificationResponse;
import com.cpass.platform.kyc.dto.VerificationResponse.DocumentAnalysisDto;
import com.cpass.platform.kyc.dto.VerificationResponse.FaceMatchDto;
import com.cpass.platform.kyc.dto.VerificationResponse.ScoreDetailsDto;
import com.cpass.platform.kyc.infra.IdentityVerificationRepository;
import com.cpass.platform.kyc.infra.VerificationAnalysisRepository;
import com.cpass.platform.kyc.infra.VerificationDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdentityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationService.class);

    private static final List<String> ALLOWED_IMAGE_TYPES = List.of(
            MediaType.IMAGE_JPEG_VALUE, "image/jpg", MediaType.IMAGE_PNG_VALUE, "image/webp");
    private static final List<String> ALLOWED_DOCUMENT_TYPES = List.of(
            MediaType.IMAGE_JPEG_VALUE, "image/jpg", MediaType.IMAGE_PNG_VALUE, "image/webp",
            MediaType.APPLICATION_PDF_VALUE);

    private final IdentityVerificationRepository identityVerificationRepository;
    private final VerificationDocumentRepository verificationDocumentRepository;
    private final VerificationAnalysisRepository verificationAnalysisRepository;
    private final VerificationWorkflowPublisher workflowPublisher;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String bucketName;

    public IdentityVerificationService(IdentityVerificationRepository identityVerificationRepository,
                                       VerificationDocumentRepository verificationDocumentRepository,
                                       VerificationAnalysisRepository verificationAnalysisRepository,
                                       VerificationWorkflowPublisher workflowPublisher,
                                       S3Client s3Client,
                                       ObjectMapper objectMapper,
                                       @Value("${app.kyc.s3.bucket}") String bucketName) {
        this.identityVerificationRepository = identityVerificationRepository;
        this.verificationDocumentRepository = verificationDocumentRepository;
        this.verificationAnalysisRepository = verificationAnalysisRepository;
        this.workflowPublisher = workflowPublisher;
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.bucketName = bucketName;
    }

    @Transactional
    public InitVerificationResponse init(UUID clientId, InitVerificationRequest request) {
        IdentityVerificationEntity verification = new IdentityVerificationEntity();
        verification.setClientId(clientId);
        verification.setReferenceId(blankToNull(request.referenceId()));
        verification.setDocumentType(request.documentType());
        verification.setFirstName(blankToNull(request.firstName()));
        verification.setLastName(blankToNull(request.lastName()));
        verification.setPhoneNumber(blankToNull(request.phoneNumber()));
        verification.setStatus(VerificationStatus.INITIATED);
        identityVerificationRepository.save(verification);

        log.info("verification init clientId={} verificationId={} documentType={}",
                clientId, verification.getId(), request.documentType());

        return new InitVerificationResponse(verification.getId(), verification.getStatus());
    }

    @Transactional
    public UploadVerificationResponse upload(UUID clientId,
                                             UUID verificationId,
                                             MultipartFile documentFront,
                                             MultipartFile documentBack,
                                             MultipartFile selfie,
                                             String captureDevice,
                                             String appVersion,
                                             String platform) {
        IdentityVerificationEntity verification = requireClientVerification(clientId, verificationId);

        if (verification.getStatus() != VerificationStatus.INITIATED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_ALREADY_SUBMITTED",
                    "Files have already been uploaded for this verification");
        }

        if (documentFront == null || documentFront.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_FORMAT",
                    "documentFront is required");
        }
        if (selfie == null || selfie.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_FORMAT",
                    "selfie is required");
        }

        validateFileType(documentFront, ALLOWED_DOCUMENT_TYPES, "documentFront");
        validateFileType(selfie, ALLOWED_IMAGE_TYPES, "selfie");
        if (documentBack != null && !documentBack.isEmpty()) {
            validateFileType(documentBack, ALLOWED_DOCUMENT_TYPES, "documentBack");
        }

        try {
            byte[] frontBytes = documentFront.getBytes();
            String frontKey = buildObjectKey(clientId, verificationId, "document-front", documentFront);
            uploadToS3(frontKey, frontBytes, resolveContentType(documentFront));

            String backKey = null;
            if (documentBack != null && !documentBack.isEmpty()) {
                byte[] backBytes = documentBack.getBytes();
                backKey = buildObjectKey(clientId, verificationId, "document-back", documentBack);
                uploadToS3(backKey, backBytes, resolveContentType(documentBack));
            }

            byte[] selfieBytes = selfie.getBytes();
            String selfieKey = buildObjectKey(clientId, verificationId, "selfie", selfie);
            uploadToS3(selfieKey, selfieBytes, resolveContentType(selfie));

            VerificationDocumentEntity doc = new VerificationDocumentEntity();
            doc.setVerification(verification);
            doc.setDocumentFrontKey(frontKey);
            doc.setDocumentBackKey(backKey);
            doc.setSelfieKey(selfieKey);
            doc.setBucketName(bucketName);
            doc.setStorageProvider("S3");
            doc.setCaptureDevice(blankToNull(captureDevice));
            doc.setAppVersion(blankToNull(appVersion));
            doc.setPlatform(blankToNull(platform));
            verificationDocumentRepository.save(doc);

            verification.setStatus(VerificationStatus.PROCESSING);
            verification.setCurrentStep(VerificationStep.DOCUMENT_ANALYSIS);
            identityVerificationRepository.save(verification);

            workflowPublisher.publish(verificationId);

            log.info("verification upload complete clientId={} verificationId={} frontKey={} selfieKey={} async=true",
                    clientId, verificationId, frontKey, selfieKey);

            return new UploadVerificationResponse(verificationId, VerificationStatus.PROCESSING);

        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("verification upload failed clientId={} verificationId={} error={}", clientId, verificationId, ex.getMessage(), ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PROCESSING_FAILED", "Failed to upload verification files");
        }
    }

    @Transactional(readOnly = true)
    public VerificationResponse getVerification(UUID clientId, UUID verificationId) {
        IdentityVerificationEntity verification = requireClientVerification(clientId, verificationId);
        Optional<VerificationAnalysisEntity> analysis = verificationAnalysisRepository.findByVerification(verification);
        return toClientResponse(verification, analysis.orElse(null));
    }

    @Transactional(readOnly = true)
    public List<IdentityVerificationEntity> listVerifications(VerificationStatus status) {
        return status == null
                ? identityVerificationRepository.findAllByOrderByCreatedAtDesc()
                : identityVerificationRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public VerificationResponse reviewVerification(UUID verificationId, AdminVerificationReviewRequest request) {
        if (request.status() != VerificationStatus.APPROVED
                && request.status() != VerificationStatus.REJECTED
                && request.status() != VerificationStatus.REVIEW) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS",
                    "Review status must be APPROVED, REJECTED, or REVIEW");
        }

        IdentityVerificationEntity verification = identityVerificationRepository.findById(verificationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND",
                        "Identity verification not found"));

        verification.setStatus(request.status());
        verification.setDecisionReason(request.reason() != null ? request.reason() : "MANUALLY_REVIEWED");
        if (request.status() == VerificationStatus.APPROVED) {
            verification.setReliabilityScore(100);
        }
        verification.setCurrentStep(VerificationStep.COMPLETED);
        identityVerificationRepository.save(verification);

        Optional<VerificationAnalysisEntity> analysis = verificationAnalysisRepository.findByVerification(verification);
        return toAdminResponse(verification, analysis.orElse(null));
    }

    public VerificationResponse toAdminResponse(IdentityVerificationEntity verification, VerificationAnalysisEntity analysis) {
        return buildResponse(verification, analysis, false);
    }

    public VerificationResponse toClientResponse(IdentityVerificationEntity verification, VerificationAnalysisEntity analysis) {
        return buildResponse(verification, analysis, true);
    }

    private VerificationResponse buildResponse(IdentityVerificationEntity verification,
                                               VerificationAnalysisEntity analysis,
                                               boolean maskSensitiveData) {
        DocumentAnalysisDto documentAnalysis = null;
        FaceMatchDto faceMatch = null;
        ScoreDetailsDto scoreDetails = null;

        if (analysis != null) {
            String documentNumber = analysis.getExtractedDocumentNumber();
            if (maskSensitiveData && documentNumber != null) {
                documentNumber = maskDocumentNumber(documentNumber);
            }

            documentAnalysis = new DocumentAnalysisDto(
                    analysis.getDocumentReadable(),
                    analysis.getDocumentExpired(),
                    verification.getDocumentType() != null ? verification.getDocumentType().name() : null,
                    documentNumber,
                    analysis.getExtractedFullName(),
                    analysis.getExtractedBirthDate() != null ? analysis.getExtractedBirthDate().toString() : null,
                    analysis.getExtractedExpiryDate() != null ? analysis.getExtractedExpiryDate().toString() : null
            );

            if (analysis.getFaceMatchScore() != null) {
                faceMatch = new FaceMatchDto(analysis.getFaceMatched(), analysis.getFaceMatchScore());
            }

            scoreDetails = parseScoreDetails(analysis.getScoreDetailsJson());
        }

        return new VerificationResponse(
                verification.getId(),
                verification.getStatus(),
                verification.getCurrentStep(),
                documentAnalysis,
                faceMatch,
                verification.getReliabilityScore(),
                scoreDetails,
                verification.getDecisionReason(),
                verification.getCreatedAt(),
                verification.getUpdatedAt()
        );
    }

    private IdentityVerificationEntity requireClientVerification(UUID clientId, UUID verificationId) {
        return identityVerificationRepository.findByIdAndClientId(verificationId, clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND",
                        "Identity verification not found"));
    }

    private void validateFileType(MultipartFile file, List<String> allowed, String fieldName) {
        String contentType = file.getContentType();
        if (contentType == null || !allowed.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_FORMAT",
                    "Unsupported file format for " + fieldName + ". Allowed: " + allowed);
        }
    }

    private void uploadToS3(String objectKey, byte[] bytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        log.info("verification s3 upload success bucket={} key={} bytes={}", bucketName, objectKey, bytes.length);
    }

    private String buildObjectKey(UUID clientId, UUID verificationId, String slot, MultipartFile file) {
        String ext = extensionFor(resolveContentType(file));
        return clientId + "/" + verificationId + "/" + slot + "-" + Instant.now().toEpochMilli() + ext;
    }

    private String resolveContentType(MultipartFile file) {
        String ct = file.getContentType();
        return (ct == null || ct.isBlank()) ? MediaType.APPLICATION_OCTET_STREAM_VALUE : ct;
    }

    private String extensionFor(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    private String maskDocumentNumber(String documentNumber) {
        if (documentNumber.length() <= 4) {
            return "****";
        }
        int visibleChars = Math.min(2, documentNumber.length() / 3);
        int maskedLength = documentNumber.length() - visibleChars * 2;
        return documentNumber.substring(0, visibleChars)
                + "*".repeat(maskedLength)
                + documentNumber.substring(documentNumber.length() - visibleChars);
    }

    private ScoreDetailsDto parseScoreDetails(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Integer> map = objectMapper.readValue(json, new TypeReference<>() {});
            return new ScoreDetailsDto(
                    map.get("documentReadability"),
                    map.get("ocrConfidence"),
                    map.get("documentValidity"),
                    map.get("faceMatch")
            );
        } catch (Exception ex) {
            log.warn("verification failed to parse score details: {}", ex.getMessage());
            return null;
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
