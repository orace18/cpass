package com.cpass.platform.kyc.service;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.RequestCorrelation;
import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.IdentityKycEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.kyc.domain.KycNextAction;
import com.cpass.platform.kyc.domain.KycReasonCode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final S3Client s3Client;
    private final KycDocumentRepository kycDocumentRepository;
    private final KycCaseRepository kycCaseRepository;
    private final IdentityRepository identityRepository;
    private final IdentityKycRepository identityKycRepository;
    private final OtpService otpService;
    private final DocumentAnalysisService documentAnalysisService;
    private final SelfieAnalysisService selfieAnalysisService;
    private final KycWorkflowPublisher workflowPublisher;
    private final KycFlowService kycFlowService;
    private final KycFlowProperties kycFlowProperties;
    private final String s3Endpoint;
    private final String bucketName;

    public KycService(S3Client s3Client,
                      KycDocumentRepository kycDocumentRepository,
                      KycCaseRepository kycCaseRepository,
                      IdentityRepository identityRepository,
                      IdentityKycRepository identityKycRepository,
                      OtpService otpService,
                      DocumentAnalysisService documentAnalysisService,
                      SelfieAnalysisService selfieAnalysisService,
                      KycWorkflowPublisher workflowPublisher,
                      KycFlowService kycFlowService,
                      KycFlowProperties kycFlowProperties,
                      @org.springframework.beans.factory.annotation.Value("${app.kyc.s3.endpoint}") String s3Endpoint,
                      @org.springframework.beans.factory.annotation.Value("${app.kyc.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.kycDocumentRepository = kycDocumentRepository;
        this.kycCaseRepository = kycCaseRepository;
        this.identityRepository = identityRepository;
        this.identityKycRepository = identityKycRepository;
        this.otpService = otpService;
        this.documentAnalysisService = documentAnalysisService;
        this.selfieAnalysisService = selfieAnalysisService;
        this.workflowPublisher = workflowPublisher;
        this.kycFlowService = kycFlowService;
        this.kycFlowProperties = kycFlowProperties;
        this.s3Endpoint = s3Endpoint;
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
        String mappedProviderType = mapDocumentTypeToProviderType(documentType);
        String originalFileName = file.getOriginalFilename();
        String incomingContentType = file.getContentType();
        long incomingFileSize = file.getSize();
        try {
            byte[] fileBytes = file.getBytes();
            String resolvedFileName = resolveFileName(file, documentType);
            String mimeType = resolveContentType(file);

            log.info("kyc upload service start clientId={} caseId={} documentType={} mappedProviderType={} fileBytes={} fileName={} originalFileName={} mimeType={} metadata={}",
                    clientId,
                    caseId,
                    documentType,
                    mappedProviderType,
                    fileBytes.length,
                    resolvedFileName,
                    originalFileName,
                    mimeType,
                    metadataJson);

            if (originalFileName == null || originalFileName.isBlank()) {
                log.warn("kyc upload missing multipart filename caseId={} documentType={} generatedFileName={}",
                        caseId,
                        documentType,
                        resolvedFileName);
            }
            if (fileBytes.length == 0) {
                log.warn("kyc upload received empty file payload caseId={} documentType={} fileName={}",
                        caseId,
                        documentType,
                        resolvedFileName);
            }

            String objectKey = clientId + "/" + kycCase.getReferenceId() + "/" + Instant.now().toEpochMilli() + "-" + resolvedFileName;
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType(mimeType)
                    .build();
            log.info("kyc upload storage start provider=S3 endpoint={} bucket={} objectKey={} contentType={} size={}",
                    s3Endpoint,
                    bucketName,
                    objectKey,
                    mimeType,
                    fileBytes.length);
            try {
                s3Client.putObject(putObjectRequest, RequestBody.fromBytes(fileBytes));
            } catch (Exception storageException) {
                log.error("kyc upload storage failed provider=S3 endpoint={} bucket={} objectKey={} errorType={} errorMessage={}",
                        s3Endpoint,
                        bucketName,
                        objectKey,
                        storageException.getClass().getName(),
                        storageException.getMessage(),
                        storageException);
                throw storageException;
            }
            log.info("kyc upload storage success provider=S3 endpoint={} bucket={} objectKey={} bytesWritten={}",
                    s3Endpoint,
                    bucketName,
                    objectKey,
                    fileBytes.length);

            boolean selfieUpload = documentType == KycDocumentType.SELFIE;
            DocumentAnalysisService.DocumentAnalysisResult analysis = null;
            SelfieAnalysisService.SelfieAnalysisResult selfieAnalysis = null;
            if (selfieUpload) {
                selfieAnalysis = selfieAnalysisService.analyze(metadataJson);
                log.info("kyc upload selfie analysis documentType={} mappedProviderType={} fileBytes={} fileName={} mimeType={} validityStatus={} faceDetectedOnSelfie={} selfieQuality={} faceDetectedOnDocument={} faceMatchScore={} faceDecision={} confidenceScore={}",
                        documentType,
                        mappedProviderType,
                        fileBytes.length,
                        resolvedFileName,
                        mimeType,
                        selfieAnalysis.validityStatus(),
                        selfieAnalysis.faceDetectedOnSelfie(),
                        selfieAnalysis.selfieQuality(),
                        selfieAnalysis.faceDetectedOnDocument(),
                        selfieAnalysis.faceMatchScore(),
                        selfieAnalysis.faceDecision(),
                        selfieAnalysis.biometricConfidenceScore());
            } else {
                analysis = documentAnalysisService.analyze(documentType, metadataJson);
                log.info("kyc upload analysis documentType={} detectedDocumentType={} supportedDocumentType={} mappedProviderType={} fileBytes={} fileName={} mimeType={} validityStatus={} documentNumberPresent={} expiryDate={} confidenceScore={}",
                        documentType,
                        analysis.detectedDocumentType(),
                        analysis.supportedDocumentType(),
                        mappedProviderType,
                        fileBytes.length,
                        resolvedFileName,
                        mimeType,
                        analysis.validityStatus(),
                        analysis.documentNumber() != null,
                        analysis.expiryDate(),
                        analysis.confidenceScore());
            }

            KycDocumentEntity document = new KycDocumentEntity();
            document.setClientId(clientId);
            document.setKycCase(kycCase);
            document.setDocumentType(documentType);
            document.setFileName(resolvedFileName);
            document.setObjectKey(objectKey);
            document.setBucketName(bucketName);
            document.setContentType(mimeType);
            document.setSizeBytes(fileBytes.length);
            document.setStatus(KycDocumentStatus.PENDING);
            if (selfieUpload) {
                document.setMetadataJson(enrichedSelfieMetadata(metadataJson, selfieAnalysis));
                document.setDocumentNumber(null);
                document.setExpiryDate(null);
                document.setValidityStatus(selfieAnalysis.validityStatus());
            } else {
                document.setMetadataJson(enrichedDocumentMetadata(metadataJson, analysis));
                document.setDocumentNumber(analysis.documentNumber());
                document.setExpiryDate(analysis.expiryDate());
                document.setValidityStatus(analysis.validityStatus());
            }
            KycDocumentEntity saved = kycDocumentRepository.save(document);
            saved.setPreviewPath("/admin/kyc/documents/" + saved.getId() + "/download");
            kycDocumentRepository.save(saved);
            log.info("kyc upload persisted documentId={} caseId={} documentType={} objectKey={} status={} validityStatus={}",
                    saved.getId(),
                    caseId,
                    documentType,
                    saved.getObjectKey(),
                    saved.getStatus(),
                    saved.getValidityStatus());

            Map<String, Object> caseMetadata = metadataOf(kycCase);
            caseMetadata.putIfAbsent("fullName", kycCase.getIdentity().getFullName());
            caseMetadata.putIfAbsent("phoneNumber", kycCase.getIdentity().getPhoneNumber());
            if (selfieUpload) {
                caseMetadata.put("selfieDocumentId", saved.getId().toString());
                caseMetadata.putAll(selfieAnalysis.biometricSignals());
            }
            kycCase.setMetadataJson(kycFlowService.writeMetadata(caseMetadata));
            kycCaseRepository.save(kycCase);

            KycDocumentEntity actionableDocument = applyImmediateResubmissionDecision(kycCase, saved);
            workflowPublisher.publish(kycCase.getId());
            log.info("kyc upload completed caseId={} documentId={} workflowTriggered=true", caseId, saved.getId());
            return toUploadResponse(kycCase, actionableDocument);
        } catch (Exception exception) {
            log.error("kyc upload failed clientId={} caseId={} documentType={} mappedProviderType={} originalFileName={} contentType={} fileSize={} metadata={} failureType={} failureMessage={}",
                    clientId,
                    caseId,
                    documentType,
                    mappedProviderType,
                    originalFileName,
                    incomingContentType,
                    incomingFileSize,
                    metadataJson,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception);
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
                && request.status() != KycCaseStatus.REVIEW
                && request.status() != KycCaseStatus.RESUBMISSION_REQUIRED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS", "Review status must be APPROVED, REJECTED, REVIEW or RESUBMISSION_REQUIRED");
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
            } else if (request.status() == KycCaseStatus.RESUBMISSION_REQUIRED) {
                document.setStatus(KycDocumentStatus.RESUBMISSION_REQUIRED);
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

    private String enrichedDocumentMetadata(String metadataJson, DocumentAnalysisService.DocumentAnalysisResult analysis) {
        Map<String, Object> metadata = new HashMap<>(documentAnalysisService.parse(metadataJson));
        metadata.put("detectedDocumentType", analysis.detectedDocumentType() == null ? null : analysis.detectedDocumentType().name());
        metadata.put("supportedDocumentType", analysis.supportedDocumentType());
        metadata.put("extractedFields", analysis.extractedFields());
        metadata.put("confidenceScore", analysis.confidenceScore());
        return kycFlowService.writeMetadata(metadata);
    }

    private String enrichedSelfieMetadata(String metadataJson, SelfieAnalysisService.SelfieAnalysisResult analysis) {
        Map<String, Object> metadata = new HashMap<>(documentAnalysisService.parse(metadataJson));
        metadata.putAll(analysis.biometricSignals());
        metadata.put("extractedFields", analysis.biometricSignals());
        metadata.put("confidenceScore", analysis.biometricConfidenceScore());
        return kycFlowService.writeMetadata(metadata);
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
        metadata.put("birthDate", request.birthDate());
        metadata.put("phoneNumber", request.phoneNumber());
        metadata.put("documentNumber", request.documentNumber());
        metadata.put("faceHash", request.faceHash());
        metadata.put("contactVerified", Boolean.TRUE.equals(request.contactVerified()));

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

    private String productStatus(KycCaseStatus status) {
        if (status == null) {
            return null;
        }
        return status == KycCaseStatus.APPROVED ? "VERIFIED" : status.name();
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
        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
        KycDocumentEntity document = latestIdentityDocument(documents).orElse(null);
        KycDocumentEntity selfie = latestDocumentOfType(documents, KycDocumentType.SELFIE).orElse(null);
        DecisionFields decisionFields = decisionFields(
                kycCase.getStatus(),
                kycCase.getStatusReason(),
                document == null ? null : document.getValidityStatus(),
                document == null ? null : document.getDocumentType()
        );
        return new KycCaseResponse(
                kycCase.getId(),
                kycCase.getReferenceId(),
                kycCase.getLevel(),
                kycCase.getStatus(),
                kycCase.getStatus(),
                kycCase.getScore(),
                kycCase.getDocumentExpiryDate(),
                document == null ? null : document.getValidityStatus(),
                kycCase.getValidUntil(),
                kycCase.getCreatedAt(),
                decisionFields.statusReason(),
                decisionFields.reasonCode(),
                decisionFields.nextAction(),
                decisionFields.recommendation(),
                responseExtractedFields(document, selfie),
                responseConfidenceScore(document, selfie),
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                productStatus(kycCase.getStatus())
        );
    }

    private KycUploadResponse toUploadResponse(KycCaseEntity kycCase, KycDocumentEntity document) {
        DecisionFields decisionFields = decisionFields(
                kycCase.getStatus(),
                kycCase.getStatusReason(),
                document.getValidityStatus(),
                document.getDocumentType()
        );
        return new KycUploadResponse(
                document.getId(),
                kycCase.getId(),
                document.getStatus(),
                document.getObjectKey(),
                document.getValidityStatus(),
                kycCase.getStatus(),
                decisionFields.statusReason(),
                decisionFields.reasonCode(),
                decisionFields.nextAction(),
                decisionFields.recommendation(),
                extractedFields(document),
                confidenceScore(document),
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                productStatus(kycCase.getStatus())
        );
    }

    private KycDocumentEntity applyImmediateResubmissionDecision(KycCaseEntity kycCase, KycDocumentEntity uploadedDocument) {
        if (uploadedDocument.getValidityStatus() != KycDocumentValidityStatus.UNREADABLE) {
            return uploadedDocument;
        }

        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
        boolean selfie = uploadedDocument.getDocumentType() == KycDocumentType.SELFIE;
        boolean hasUsableIdentityDocument = documents.stream()
                .filter(this::isIdentityDocument)
                .anyMatch(document -> document.getValidityStatus() == KycDocumentValidityStatus.VALID);

        String reason = resubmissionReason(uploadedDocument, selfie);
        uploadedDocument.setStatus(KycDocumentStatus.RESUBMISSION_REQUIRED);
        uploadedDocument.setReviewReason(reason);
        uploadedDocument.setReviewedAt(Instant.now());
        KycDocumentEntity savedDocument = kycDocumentRepository.save(uploadedDocument);

        if (!selfie && hasUsableIdentityDocument) {
            return savedDocument;
        }

        kycCase.setStatus(KycCaseStatus.RESUBMISSION_REQUIRED);
        kycCase.setScore(30);
        kycCase.setStatusReason(reason);
        kycCase.setValidUntil(null);
        kycCase.setLastProcessedAt(Instant.now());
        kycCaseRepository.save(kycCase);
        identityKycRepository.findByKycCase(kycCase).ifPresent(mapping -> {
            mapping.setStatus(kycCase.getStatus());
            mapping.setValidUntil(null);
            identityKycRepository.save(mapping);
        });

        return savedDocument;
    }

    private String resubmissionReason(KycDocumentEntity document, boolean selfie) {
        if (selfie) {
            return KycFlowService.REASON_SELFIE_UNREADABLE;
        }
        if (isUnsupportedDocumentType(document)) {
            return KycFlowService.REASON_UNSUPPORTED_DOCUMENT_TYPE;
        }
        return KycFlowService.REASON_DOCUMENT_UNREADABLE;
    }

    private Optional<KycDocumentEntity> latestIdentityDocument(List<KycDocumentEntity> documents) {
        return documents.stream()
                .filter(this::isIdentityDocument)
                .reduce((first, second) -> second);
    }

    private Optional<KycDocumentEntity> latestDocumentOfType(List<KycDocumentEntity> documents, KycDocumentType documentType) {
        return documents.stream()
                .filter(document -> document.getDocumentType() == documentType)
                .reduce((first, second) -> second);
    }

    private boolean isIdentityDocument(KycDocumentEntity document) {
        return document.getDocumentType() != KycDocumentType.SELFIE
                && document.getDocumentType() != KycDocumentType.FIELD_AGENT_PHOTO;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractedFields(KycDocumentEntity document) {
        if (document == null) {
            return null;
        }
        Object fields = documentMetadata(document).get("extractedFields");
        if (fields instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private Map<String, Object> responseExtractedFields(KycDocumentEntity identityDocument, KycDocumentEntity selfieDocument) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> identityFields = extractedFields(identityDocument);
        if (identityFields != null) {
            fields.putAll(identityFields);
        }
        Map<String, Object> biometricSignals = extractedFields(selfieDocument);
        if (biometricSignals != null && !biometricSignals.isEmpty()) {
            fields.put("biometricSignals", biometricSignals);
        }
        return fields.isEmpty() ? null : fields;
    }

    private Integer confidenceScore(KycDocumentEntity document) {
        if (document == null) {
            return null;
        }
        return asInteger(documentMetadata(document).get("confidenceScore"));
    }

    private Integer responseConfidenceScore(KycDocumentEntity identityDocument, KycDocumentEntity selfieDocument) {
        Integer identityConfidence = confidenceScore(identityDocument);
        return identityConfidence == null ? confidenceScore(selfieDocument) : identityConfidence;
    }

    private boolean isUnsupportedDocumentType(KycDocumentEntity document) {
        Object supported = documentMetadata(document).get("supportedDocumentType");
        return supported instanceof Boolean bool && !bool;
    }

    private Map<String, Object> documentMetadata(KycDocumentEntity document) {
        return documentAnalysisService.parse(document.getMetadataJson());
    }

    private DecisionFields decisionFields(KycCaseStatus caseStatus,
                                          String statusReason,
                                          KycDocumentValidityStatus validityStatus,
                                          KycDocumentType documentType) {
        KycReasonCode reasonCode = reasonCode(caseStatus, statusReason, validityStatus, documentType);
        KycNextAction nextAction = nextAction(reasonCode, caseStatus);
        return new DecisionFields(
                statusReason == null ? defaultStatusReason(reasonCode, caseStatus) : statusReason,
                reasonCode,
                nextAction,
                recommendation(reasonCode, caseStatus)
        );
    }

    private KycReasonCode reasonCode(KycCaseStatus caseStatus,
                                     String statusReason,
                                     KycDocumentValidityStatus validityStatus,
                                     KycDocumentType documentType) {
        String normalizedReason = statusReason == null ? "" : statusReason.toLowerCase(Locale.ROOT);
        if (caseStatus == KycCaseStatus.RESUBMISSION_REQUIRED) {
            if (documentType == KycDocumentType.SELFIE || normalizedReason.contains("selfie")) {
                return KycReasonCode.SELFIE_UNREADABLE;
            }
            if (normalizedReason.contains("declared")) {
                return KycReasonCode.DECLARED_INFO_MISMATCH;
            }
            if (normalizedReason.contains("unsupported")) {
                return KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE;
            }
            return KycReasonCode.DOCUMENT_UNREADABLE;
        }
        if (validityStatus == KycDocumentValidityStatus.UNREADABLE) {
            return documentType == KycDocumentType.SELFIE
                    ? KycReasonCode.SELFIE_UNREADABLE
                    : KycReasonCode.DOCUMENT_UNREADABLE;
        }
        if (caseStatus == KycCaseStatus.REJECTED && normalizedReason.contains("expired")) {
            return KycReasonCode.DOCUMENT_EXPIRED;
        }
        if (caseStatus == KycCaseStatus.REVIEW) {
            return KycReasonCode.MANUAL_REVIEW_REQUIRED;
        }
        return null;
    }

    private KycNextAction nextAction(KycReasonCode reasonCode, KycCaseStatus caseStatus) {
        if (reasonCode == KycReasonCode.SELFIE_UNREADABLE) {
            return KycNextAction.RETRY_SELFIE_CAPTURE;
        }
        if (reasonCode == KycReasonCode.DOCUMENT_UNREADABLE) {
            return KycNextAction.RESUBMIT_DOCUMENT;
        }
        if (reasonCode == KycReasonCode.DOCUMENT_EXPIRED) {
            return KycNextAction.RESUBMIT_DOCUMENT;
        }
        if (reasonCode == KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE) {
            return KycNextAction.RESUBMIT_DOCUMENT;
        }
        if (reasonCode == KycReasonCode.DECLARED_INFO_MISMATCH) {
            return KycNextAction.UPDATE_DECLARED_INFO;
        }
        if (reasonCode == KycReasonCode.MANUAL_REVIEW_REQUIRED || caseStatus == KycCaseStatus.REVIEW) {
            return KycNextAction.WAIT_FOR_REVIEW;
        }
        return null;
    }

    private String defaultStatusReason(KycReasonCode reasonCode, KycCaseStatus caseStatus) {
        if (reasonCode == KycReasonCode.SELFIE_UNREADABLE) {
            return KycFlowService.REASON_SELFIE_UNREADABLE;
        }
        if (reasonCode == KycReasonCode.DOCUMENT_UNREADABLE) {
            return KycFlowService.REASON_DOCUMENT_UNREADABLE;
        }
        if (reasonCode == KycReasonCode.DECLARED_INFO_MISMATCH) {
            return KycFlowService.REASON_DECLARED_INFO_MISMATCH;
        }
        if (reasonCode == KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE) {
            return KycFlowService.REASON_UNSUPPORTED_DOCUMENT_TYPE;
        }
        if (reasonCode == KycReasonCode.DOCUMENT_EXPIRED) {
            return KycFlowService.REASON_DOCUMENT_EXPIRED;
        }
        if (caseStatus == KycCaseStatus.REVIEW) {
            return "Manual review required.";
        }
        return null;
    }

    private String recommendation(KycReasonCode reasonCode, KycCaseStatus caseStatus) {
        if (reasonCode == KycReasonCode.SELFIE_UNREADABLE) {
            return "Ask the user to retake a well-lit selfie and submit it again.";
        }
        if (reasonCode == KycReasonCode.DOCUMENT_UNREADABLE) {
            return "Ask the user to resubmit a clear, uncropped document image with readable text.";
        }
        if (reasonCode == KycReasonCode.DECLARED_INFO_MISMATCH) {
            return "Ask the user to correct the declared identity information or submit the matching document.";
        }
        if (reasonCode == KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE) {
            return "Ask the user to submit a passport, national ID, voter card or driver license.";
        }
        if (reasonCode == KycReasonCode.DOCUMENT_EXPIRED) {
            return "Ask the user to submit a non-expired document.";
        }
        if (caseStatus == KycCaseStatus.REVIEW) {
            return "Wait for an operator review before asking the user to retry.";
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private String mapDocumentTypeToProviderType(KycDocumentType documentType) {
        return documentType.name();
    }

    private String resolveFileName(MultipartFile file, KycDocumentType documentType) {
        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        if (!originalFileName.isBlank()) {
            return originalFileName;
        }
        return documentType.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + extensionFor(resolveContentType(file));
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String normalized = fileName.replace("\\", "/");
        int lastSeparator = normalized.lastIndexOf('/');
        return (lastSeparator >= 0 ? normalized.substring(lastSeparator + 1) : normalized).trim();
    }

    private String resolveContentType(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
    }

    private String extensionFor(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case MediaType.IMAGE_JPEG_VALUE, "image/jpg" -> ".jpg";
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case "image/webp" -> ".webp";
            case MediaType.APPLICATION_PDF_VALUE -> ".pdf";
            default -> "";
        };
    }

    private record ReuseDecision(KycReuseStatus status, KycCaseEntity sourceCase) {
    }

    private record DecisionFields(String statusReason,
                                  KycReasonCode reasonCode,
                                  KycNextAction nextAction,
                                  String recommendation) {
    }
}
