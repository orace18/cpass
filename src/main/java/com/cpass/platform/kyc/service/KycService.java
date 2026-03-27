package com.cpass.platform.kyc.service;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.IdentityKycEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.kyc.domain.KycReuseStatus;
import com.cpass.platform.kyc.dto.AdminKycReviewRequest;
import com.cpass.platform.kyc.dto.KycCaseResponse;
import com.cpass.platform.kyc.dto.KycCheckOrInitRequest;
import com.cpass.platform.kyc.dto.KycCheckOrInitResponse;
import com.cpass.platform.kyc.dto.KycContactVerificationRequest;
import com.cpass.platform.kyc.dto.KycFieldVerificationRequest;
import com.cpass.platform.kyc.dto.KycSocialValidationRequest;
import com.cpass.platform.kyc.dto.KycUploadResponse;
import com.cpass.platform.kyc.infra.IdentityKycRepository;
import com.cpass.platform.kyc.infra.IdentityRepository;
import com.cpass.platform.kyc.infra.KycCaseRepository;
import com.cpass.platform.kyc.infra.KycDocumentRepository;
import com.cpass.platform.otp.dto.OtpVerifyResponse;
import com.cpass.platform.otp.service.OtpService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class KycService {

    private final S3Client s3Client;
    private final KycDocumentRepository kycDocumentRepository;
    private final KycCaseRepository kycCaseRepository;
    private final IdentityRepository identityRepository;
    private final IdentityKycRepository identityKycRepository;
    private final OtpService otpService;
    private final DocumentAnalysisService documentAnalysisService;
    private final KycWorkflowPublisher workflowPublisher;
    private final KycFlowService kycFlowService;
    private final KycFlowProperties kycFlowProperties;
    private final String bucketName;

    public KycService(S3Client s3Client,
                      KycDocumentRepository kycDocumentRepository,
                      KycCaseRepository kycCaseRepository,
                      IdentityRepository identityRepository,
                      IdentityKycRepository identityKycRepository,
                      OtpService otpService,
                      DocumentAnalysisService documentAnalysisService,
                      KycWorkflowPublisher workflowPublisher,
                      KycFlowService kycFlowService,
                      KycFlowProperties kycFlowProperties,
                      @org.springframework.beans.factory.annotation.Value("${app.kyc.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.kycDocumentRepository = kycDocumentRepository;
        this.kycCaseRepository = kycCaseRepository;
        this.identityRepository = identityRepository;
        this.identityKycRepository = identityKycRepository;
        this.otpService = otpService;
        this.documentAnalysisService = documentAnalysisService;
        this.workflowPublisher = workflowPublisher;
        this.kycFlowService = kycFlowService;
        this.kycFlowProperties = kycFlowProperties;
        this.bucketName = bucketName;
    }

    @Transactional
    public KycCheckOrInitResponse checkOrInit(UUID clientId, KycLevel clientRequiredLevel, KycCheckOrInitRequest request) {
        KycLevel requiredLevel = request.requestedLevel() == null
                ? clientRequiredLevel
                : KycLevel.max(clientRequiredLevel, request.requestedLevel());

        IdentityEntity identity = findIdentity(request.documentNumber(), request.phoneNumber())
                .map(existing -> enrichIdentity(existing, request))
                .orElseGet(() -> identityRepository.save(newIdentity(request)));

        ReuseDecision reuseDecision = evaluateReuse(identity, requiredLevel);
        KycCaseEntity kycCase = createCase(clientId, identity, request.userId(), requiredLevel, reuseDecision, request);
        identityKycRepository.save(createMapping(identity, kycCase));

        if (reuseDecision.status() == KycReuseStatus.REUSED) {
            return new KycCheckOrInitResponse(reuseDecision.status(), requiredLevel, kycCase.getId(), kycCase.getReferenceId());
        }

        workflowPublisher.publish(kycCase.getId());
        return new KycCheckOrInitResponse(reuseDecision.status(), requiredLevel, kycCase.getId(), kycCase.getReferenceId());
    }

    @Transactional(readOnly = true)
    public KycCaseResponse getCase(UUID clientId, UUID caseId) {
        KycCaseEntity kycCase = kycCaseRepository.findByIdAndClientId(caseId, clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_CASE_NOT_FOUND", "KYC case not found"));
        return toResponse(kycCase);
    }

    @Transactional
    public KycCaseResponse verifyContact(UUID clientId, UUID caseId, KycContactVerificationRequest request) {
        KycCaseEntity kycCase = requireClientCase(clientId, caseId);
        OtpVerifyResponse verifyResponse = otpService.verifyOtp(clientId, request.otpReferenceId(), request.otpCode());
        if (!verifyResponse.verified()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "OTP verification failed");
        }
        Map<String, Object> metadata = metadataOf(kycCase);
        metadata.put("contactVerified", true);
        kycCase.setMetadataJson(kycFlowService.writeMetadata(metadata));
        kycCaseRepository.save(kycCase);
        workflowPublisher.publish(kycCase.getId());
        return toResponse(kycCase);
    }

    @Transactional
    public KycUploadResponse upload(UUID clientId,
                                    UUID caseId,
                                    KycDocumentType documentType,
                                    MultipartFile file,
                                    String metadataJson) {
        KycCaseEntity kycCase = requireClientCase(clientId, caseId);
        try {
            String objectKey = clientId + "/" + kycCase.getReferenceId() + "/" + Instant.now().toEpochMilli() + "-" + file.getOriginalFilename();
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(file.getBytes()));

            DocumentAnalysisService.DocumentAnalysisResult analysis = documentAnalysisService.analyze(metadataJson);

            KycDocumentEntity document = new KycDocumentEntity();
            document.setClientId(clientId);
            document.setKycCase(kycCase);
            document.setDocumentType(documentType);
            document.setFileName(file.getOriginalFilename());
            document.setObjectKey(objectKey);
            document.setBucketName(bucketName);
            document.setContentType(file.getContentType());
            document.setSizeBytes(file.getSize());
            document.setStatus(KycDocumentStatus.PENDING);
            document.setMetadataJson(metadataJson);
            document.setDocumentNumber(analysis.documentNumber());
            document.setExpiryDate(analysis.expiryDate());
            document.setValidityStatus(analysis.validityStatus());
            KycDocumentEntity saved = kycDocumentRepository.save(document);
            saved.setPreviewPath("/admin/kyc/documents/" + saved.getId() + "/download");
            kycDocumentRepository.save(saved);

            Map<String, Object> caseMetadata = metadataOf(kycCase);
            caseMetadata.putIfAbsent("fullName", kycCase.getIdentity().getFullName());
            caseMetadata.putIfAbsent("phoneNumber", kycCase.getIdentity().getPhoneNumber());
            if (analysis.faceHash() != null) {
                caseMetadata.put("faceHash", analysis.faceHash());
            }
            if (analysis.faceMatchScore() != null) {
                caseMetadata.put("faceMatchScore", analysis.faceMatchScore());
            }
            kycCase.setMetadataJson(kycFlowService.writeMetadata(caseMetadata));
            kycCaseRepository.save(kycCase);

            workflowPublisher.publish(kycCase.getId());
            return new KycUploadResponse(saved.getId(), saved.getStatus(), saved.getObjectKey(), saved.getValidityStatus());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "KYC_UPLOAD_FAILED", "Failed to upload KYC document");
        }
    }

    @Transactional
    public KycCaseResponse recordFieldVerification(UUID clientId, UUID caseId, KycFieldVerificationRequest request) {
        KycCaseEntity kycCase = requireClientCase(clientId, caseId);
        Map<String, Object> metadata = metadataOf(kycCase);
        metadata.put("fieldVerification", Map.of(
                "latitude", request.latitude(),
                "longitude", request.longitude(),
                "photoUrl", request.photoUrl(),
                "verifiedAt", Instant.now().toString()
        ));
        kycCase.setMetadataJson(kycFlowService.writeMetadata(metadata));
        kycCaseRepository.save(kycCase);
        workflowPublisher.publish(kycCase.getId());
        return toResponse(kycCase);
    }

    @Transactional
    public KycCaseResponse recordSocialValidation(UUID clientId, UUID caseId, KycSocialValidationRequest request) {
        KycCaseEntity kycCase = requireClientCase(clientId, caseId);
        Map<String, Object> metadata = metadataOf(kycCase);
        metadata.put("socialGuarantee", Map.of(
                "guarantorName", request.guarantorName(),
                "guarantorPhone", request.guarantorPhone(),
                "notes", request.notes(),
                "validatedAt", Instant.now().toString()
        ));
        kycCase.setMetadataJson(kycFlowService.writeMetadata(metadata));
        kycCaseRepository.save(kycCase);
        workflowPublisher.publish(kycCase.getId());
        return toResponse(kycCase);
    }

    @Transactional(readOnly = true)
    public List<KycCaseEntity> listCases(KycCaseStatus status) {
        return status == null
                ? kycCaseRepository.findAllByOrderByCreatedAtDesc()
                : kycCaseRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public List<KycDocumentEntity> documentsFor(KycCaseEntity kycCase) {
        return kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
    }

    @Transactional
    public KycCaseResponse reviewCase(UUID caseId, AdminKycReviewRequest request) {
        KycCaseEntity kycCase = kycCaseRepository.findById(caseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_CASE_NOT_FOUND", "KYC case not found"));
        if (request.status() != KycCaseStatus.APPROVED
                && request.status() != KycCaseStatus.REJECTED
                && request.status() != KycCaseStatus.REVIEW) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS", "Review status must be APPROVED, REJECTED or REVIEW");
        }

        kycCase.setStatus(request.status());
        kycCase.setStatusReason(request.reason());
        kycCase.setLastProcessedAt(Instant.now());
        kycCase.setValidUntil(request.status() == KycCaseStatus.APPROVED
                ? Instant.now().plus(kycFlowProperties.validityPeriod())
                : null);
        kycCaseRepository.save(kycCase);
        identityKycRepository.findByKycCase(kycCase).ifPresent(mapping -> {
            mapping.setStatus(request.status());
            mapping.setValidUntil(kycCase.getValidUntil());
            identityKycRepository.save(mapping);
        });

        latestIdentityDocument(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).ifPresent(document -> {
            if (request.status() == KycCaseStatus.APPROVED) {
                document.setStatus(KycDocumentStatus.APPROVED);
            } else if (request.status() == KycCaseStatus.REJECTED) {
                document.setStatus(KycDocumentStatus.REJECTED);
                document.setReviewReason(request.reason());
            }
            document.setReviewedAt(Instant.now());
            kycDocumentRepository.save(document);
        });
        return toResponse(kycCase);
    }

    @Transactional(readOnly = true)
    public DownloadedDocument download(UUID documentId) {
        KycDocumentEntity document = kycDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_DOCUMENT_NOT_FOUND", "KYC document not found"));
        ResponseBytes<GetObjectResponse> payload = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(document.getBucketName())
                .key(document.getObjectKey())
                .build());
        return new DownloadedDocument(payload.asByteArray(), document.getContentType(), document.getFileName());
    }

    public record DownloadedDocument(byte[] content, String contentType, String fileName) {
    }

    private KycCaseEntity requireClientCase(UUID clientId, UUID caseId) {
        return kycCaseRepository.findByIdAndClientId(caseId, clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_CASE_NOT_FOUND", "KYC case not found"));
    }

    private Map<String, Object> metadataOf(KycCaseEntity kycCase) {
        Map<String, Object> metadata = new HashMap<>(documentAnalysisService.parse(kycCase.getMetadataJson()));
        metadata.putIfAbsent("fullName", kycCase.getIdentity().getFullName());
        metadata.putIfAbsent("phoneNumber", kycCase.getIdentity().getPhoneNumber());
        metadata.putIfAbsent("documentNumber", kycCase.getIdentity().getDocumentNumber());
        return metadata;
    }

    private Optional<IdentityEntity> findIdentity(String documentNumber, String phoneNumber) {
        if (documentNumber != null && !documentNumber.isBlank()) {
            Optional<IdentityEntity> byDocument = identityRepository.findByDocumentNumber(documentNumber);
            if (byDocument.isPresent()) {
                return byDocument;
            }
        }
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            return identityRepository.findFirstByPhoneNumberOrderByCreatedAtDesc(phoneNumber);
        }
        return Optional.empty();
    }

    private IdentityEntity newIdentity(KycCheckOrInitRequest request) {
        IdentityEntity identity = new IdentityEntity();
        identity.setDocumentNumber(blankToNull(request.documentNumber()));
        identity.setPhoneNumber(blankToNull(request.phoneNumber()));
        identity.setFullName(blankToNull(request.fullName()));
        identity.setFaceHash(blankToNull(request.faceHash()));
        return identity;
    }

    private IdentityEntity enrichIdentity(IdentityEntity identity, KycCheckOrInitRequest request) {
        if (identity.getDocumentNumber() == null) {
            identity.setDocumentNumber(blankToNull(request.documentNumber()));
        }
        if (identity.getPhoneNumber() == null) {
            identity.setPhoneNumber(blankToNull(request.phoneNumber()));
        }
        if (identity.getFullName() == null) {
            identity.setFullName(blankToNull(request.fullName()));
        }
        if (identity.getFaceHash() == null) {
            identity.setFaceHash(blankToNull(request.faceHash()));
        }
        return identityRepository.save(identity);
    }

    private ReuseDecision evaluateReuse(IdentityEntity identity, KycLevel requiredLevel) {
        List<IdentityKycEntity> approvedMappings = identityKycRepository.findByIdentityAndStatusOrderByCreatedAtDesc(identity, KycCaseStatus.APPROVED);
        Optional<IdentityKycEntity> validMapping = approvedMappings.stream()
                .filter(this::isStillValid)
                .filter(mapping -> mapping.getLevel().satisfies(requiredLevel))
                .findFirst();
        if (validMapping.isPresent()) {
            return new ReuseDecision(KycReuseStatus.REUSED, validMapping.get().getKycCase());
        }

        Optional<IdentityKycEntity> anyValidApproved = approvedMappings.stream()
                .filter(this::isStillValid)
                .findFirst();
        if (anyValidApproved.isPresent()) {
            return new ReuseDecision(KycReuseStatus.NEED_UPGRADE, anyValidApproved.get().getKycCase());
        }

        boolean hasExpiredApproved = approvedMappings.stream().anyMatch(mapping -> !isStillValid(mapping));
        if (hasExpiredApproved) {
            return new ReuseDecision(KycReuseStatus.REVALIDATION_REQUIRED, approvedMappings.getFirst().getKycCase());
        }
        return new ReuseDecision(KycReuseStatus.NEW, null);
    }

    private boolean isStillValid(IdentityKycEntity mapping) {
        if (mapping.getValidUntil() == null || mapping.getValidUntil().isBefore(Instant.now())) {
            return false;
        }
        LocalDate expiryDate = mapping.getKycCase().getDocumentExpiryDate();
        return expiryDate == null || !expiryDate.isBefore(LocalDate.now());
    }

    private KycCaseEntity createCase(UUID clientId,
                                     IdentityEntity identity,
                                     String userId,
                                     KycLevel requiredLevel,
                                     ReuseDecision reuseDecision,
                                     KycCheckOrInitRequest request) {
        KycCaseEntity kycCase = new KycCaseEntity();
        kycCase.setClientId(clientId);
        kycCase.setIdentity(identity);
        kycCase.setUserId(userId);
        kycCase.setLevel(requiredLevel);
        kycCase.setReferenceId("kyc_" + UUID.randomUUID().toString().replace("-", ""));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fullName", request.fullName());
        metadata.put("phoneNumber", request.phoneNumber());
        metadata.put("documentNumber", request.documentNumber());
        metadata.put("faceHash", request.faceHash());

        if (reuseDecision.status() == KycReuseStatus.REUSED && reuseDecision.sourceCase() != null) {
            KycCaseEntity source = reuseDecision.sourceCase();
            kycCase.setStatus(KycCaseStatus.APPROVED);
            kycCase.setScore(source.getScore());
            kycCase.setValidUntil(source.getValidUntil());
            kycCase.setDocumentExpiryDate(source.getDocumentExpiryDate());
            metadata.put("reused", true);
            metadata.put("reuseSourceReference", source.getReferenceId());
        } else {
            kycCase.setStatus(KycCaseStatus.INITIATED);
            if (reuseDecision.sourceCase() != null) {
                metadata.put("reuseSourceReference", reuseDecision.sourceCase().getReferenceId());
            }
        }

        kycCase.setMetadataJson(kycFlowService.writeMetadata(metadata));
        return kycCaseRepository.save(kycCase);
    }

    private IdentityKycEntity createMapping(IdentityEntity identity, KycCaseEntity kycCase) {
        IdentityKycEntity mapping = new IdentityKycEntity();
        mapping.setIdentity(identity);
        mapping.setKycCase(kycCase);
        mapping.setLevel(kycCase.getLevel());
        mapping.setStatus(kycCase.getStatus());
        mapping.setValidUntil(kycCase.getValidUntil());
        return mapping;
    }

    private KycCaseResponse toResponse(KycCaseEntity kycCase) {
        KycDocumentEntity document = latestIdentityDocument(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).orElse(null);
        return new KycCaseResponse(
                kycCase.getId(),
                kycCase.getReferenceId(),
                kycCase.getLevel(),
                kycCase.getStatus(),
                kycCase.getScore(),
                kycCase.getDocumentExpiryDate(),
                document == null ? null : document.getValidityStatus(),
                kycCase.getValidUntil(),
                kycCase.getCreatedAt()
        );
    }

    private Optional<KycDocumentEntity> latestIdentityDocument(List<KycDocumentEntity> documents) {
        return documents.stream()
                .filter(document -> document.getDocumentType() != KycDocumentType.SELFIE && document.getDocumentType() != KycDocumentType.FIELD_AGENT_PHOTO)
                .reduce((first, second) -> second);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ReuseDecision(KycReuseStatus status, KycCaseEntity sourceCase) {
    }
}
