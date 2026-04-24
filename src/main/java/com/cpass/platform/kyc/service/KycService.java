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
    private final KycImageProcessingService imageProcessingService;
    private final KycWorkflowPublisher workflowPublisher;
    private final KycFlowService kycFlowService;
    private final KycFlowProperties kycFlowProperties;
    private final KycOperationalMetricsService metricsService;
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
                      KycImageProcessingService imageProcessingService,
                      KycWorkflowPublisher workflowPublisher,
                      KycFlowService kycFlowService,
                      KycFlowProperties kycFlowProperties,
                      KycOperationalMetricsService metricsService,
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
        this.imageProcessingService = imageProcessingService;
        this.workflowPublisher = workflowPublisher;
        this.kycFlowService = kycFlowService;
        this.kycFlowProperties = kycFlowProperties;
        this.metricsService = metricsService;
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
        log.info("kyc case initialized requestId={} clientId={} caseId={} userId={} level={} reuseStatus={} status={}",
                RequestCorrelation.requestId(),
                clientId,
                kycCase.getId(),
                request.userId(),
                requiredLevel,
                reuseDecision.status(),
                kycCase.getStatus());

        if (reuseDecision.status() == KycReuseStatus.REUSED) {
            return new KycCheckOrInitResponse(reuseDecision.status(), requiredLevel, kycCase.getId(), kycCase.getReferenceId());
        }

        log.info("kyc case awaiting documents requestId={} clientId={} caseId={} workflowTriggered=false workflowDecision={}",
                RequestCorrelation.requestId(),
                clientId,
                kycCase.getId(),
                KycFlowService.WORKFLOW_WAITING_FOR_DOCUMENTS);
        return new KycCheckOrInitResponse(reuseDecision.status(), requiredLevel, kycCase.getId(), kycCase.getReferenceId());
    }

    public KycCaseResponse getCase(UUID clientId, UUID caseId) {
        KycCaseEntity kycCase = kycCaseRepository.findByIdAndClientId(caseId, clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_CASE_NOT_FOUND", "KYC case not found"));

        if (kycCase.getStatus() == KycCaseStatus.INITIATED) {
            kycCase = recoverInitiatedCase(clientId, caseId, kycCase);
        }

        return toResponse(kycCase);
    }

    private KycCaseEntity recoverInitiatedCase(UUID clientId, UUID caseId, KycCaseEntity kycCase) {
        log.info("kyc recovery case loaded requestId={} clientId={} caseId={} status={} level={} createdAt={} updatedAt={} metadataPresent={} metadataLength={}",
                RequestCorrelation.requestId(),
                clientId,
                caseId,
                kycCase.getStatus(),
                kycCase.getLevel(),
                kycCase.getCreatedAt(),
                kycCase.getUpdatedAt(),
                kycCase.getMetadataJson() != null && !kycCase.getMetadataJson().isBlank(),
                kycCase.getMetadataJson() == null ? 0 : kycCase.getMetadataJson().length());

        List<KycDocumentEntity> documents = recoverDocumentLinksIfPossible(clientId, caseId, kycCase);
        boolean hasIdentity = documents.stream().anyMatch(this::isIdentityDocument);
        boolean hasSelfie = documents.stream().anyMatch(d -> d.getDocumentType() == KycDocumentType.SELFIE);
        List<String> documentTypes = documents.stream()
                .map(document -> document.getId() + ":" + document.getDocumentType() + ":" + document.getStatus() + ":" + document.getValidityStatus())
                .toList();

        log.info("kyc recovery docs found requestId={} clientId={} caseId={} count={} hasIdentity={} hasSelfie={} documents={}",
                RequestCorrelation.requestId(),
                clientId,
                caseId,
                documents.size(),
                hasIdentity,
                hasSelfie,
                documentTypes);

        if (documents.isEmpty()) {
            log.info("kyc recovery no-op requestId={} clientId={} caseId={} reason=no_documents_found", RequestCorrelation.requestId(), clientId, caseId);
            return kycCase;
        }

        log.info("kyc recovery flow decision started requestId={} clientId={} caseId={} documentCount={}",
                RequestCorrelation.requestId(),
                clientId,
                caseId,
                documents.size());
        kycFlowService.process(caseId);
        KycCaseEntity refreshed = kycCaseRepository.findByIdAndClientId(caseId, clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_CASE_NOT_FOUND", "KYC case not found"));
        log.info("kyc recovery flow decision result requestId={} clientId={} caseId={} previousStatus={} finalStatus={} score={} reason={}",
                RequestCorrelation.requestId(),
                clientId,
                caseId,
                kycCase.getStatus(),
                refreshed.getStatus(),
                refreshed.getScore(),
                refreshed.getStatusReason());
        return refreshed;
    }

    private List<KycDocumentEntity> recoverDocumentLinksIfPossible(UUID clientId, UUID caseId, KycCaseEntity kycCase) {
        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
        if (!documents.isEmpty()) {
            return documents;
        }

        Instant createdAt = kycCase.getCreatedAt() == null ? Instant.now() : kycCase.getCreatedAt();
        Instant from = createdAt.minusSeconds(900);
        Instant to = Instant.now().plusSeconds(60);
        List<KycDocumentEntity> orphanWindowDocuments = kycDocumentRepository
                .findByClientIdAndKycCaseIsNullAndCreatedAtBetweenOrderByCreatedAtAsc(clientId, from, to);
        List<KycDocumentEntity> referenceDocuments = kycCase.getReferenceId() == null || kycCase.getReferenceId().isBlank()
                ? List.of()
                : kycDocumentRepository.findByClientIdAndObjectKeyContainingOrderByCreatedAtAsc(clientId, "/" + kycCase.getReferenceId() + "/");
        List<KycDocumentEntity> recoverableOrphans = kycCase.getReferenceId() == null || kycCase.getReferenceId().isBlank()
                ? List.of()
                : kycDocumentRepository.findByClientIdAndKycCaseIsNullAndObjectKeyContainingOrderByCreatedAtAsc(clientId, "/" + kycCase.getReferenceId() + "/");

        if (!recoverableOrphans.isEmpty()) {
            recoverableOrphans.forEach(document -> document.setKycCase(kycCase));
            kycDocumentRepository.saveAll(recoverableOrphans);
            kycDocumentRepository.flush();
        }

        log.info("kyc recovery linkage diagnostic requestId={} clientId={} caseId={} referenceId={} linkedCountBefore=0 orphanWindowCount={} referenceObjectCount={} attachedCount={} orphanWindowDocuments={} referenceDocuments={}",
                RequestCorrelation.requestId(),
                clientId,
                caseId,
                kycCase.getReferenceId(),
                orphanWindowDocuments.size(),
                referenceDocuments.size(),
                recoverableOrphans.size(),
                documentDiagnostics(orphanWindowDocuments),
                documentDiagnostics(referenceDocuments));

        return recoverableOrphans.isEmpty()
                ? documents
                : kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
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

            log.info("kyc upload service start requestId={} clientId={} caseId={} documentType={} mappedProviderType={} fileBytes={} fileName={} originalFileName={} mimeType={} metadataSummary={}",
                    RequestCorrelation.requestId(),
                    clientId,
                    caseId,
                    documentType,
                    mappedProviderType,
                    fileBytes.length,
                    resolvedFileName,
                    originalFileName,
                    mimeType,
                    metadataSummary(metadataJson));

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

            KycImageProcessingService.ImageProcessingResult imageProcessing =
                    imageProcessingService.prepareOcrReady(fileBytes, mimeType);

            long timestamp = Instant.now().toEpochMilli();
            String originalObjectKey = objectKey(clientId, kycCase.getReferenceId(), "original", timestamp, resolvedFileName);
            uploadToS3(originalObjectKey, fileBytes, mimeType);

            String objectKey = originalObjectKey;
            byte[] finalBytes = fileBytes;
            String finalMimeType = mimeType;
            if (imageProcessing.processed()) {
                objectKey = objectKey(clientId, kycCase.getReferenceId(), "ocr-ready", timestamp, resolvedFileName);
                finalBytes = imageProcessing.processedBytes();
                finalMimeType = imageProcessing.processedMimeType();
                uploadToS3(objectKey, finalBytes, finalMimeType);
            }

            boolean selfieUpload = documentType == KycDocumentType.SELFIE;
            KycImageProcessingService.MrzImageProcessingResult mrzImageProcessing = null;
            String mrzObjectKey = null;
            if (!selfieUpload) {
                mrzImageProcessing = imageProcessingService.prepareMrzReady(fileBytes, mimeType);
                if (mrzImageProcessing.processed()) {
                    mrzObjectKey = objectKey(clientId, kycCase.getReferenceId(), "ocr-mrz-ready", timestamp, resolvedFileName);
                    uploadToS3(mrzObjectKey, mrzImageProcessing.processedBytes(), mrzImageProcessing.processedMimeType());
                }
                log.info("kyc mrz image technical accountHolderId={} documentSlot={} mrzCandidateDetected={} mrzCropCoordinates={} transformationsApplied={} mrzObjectKey={} processedSizeBytes={}",
                        accountHolderId(kycCase, metadataJson),
                        documentType,
                        mrzImageProcessing.processed(),
                        mrzImageProcessing.cropCoordinates(),
                        mrzImageProcessing.transformationsApplied(),
                        mrzObjectKey,
                        mrzImageProcessing.processedBytes().length);
            }

            log.info("kyc image technical accountHolderId={} documentSlot={} originalWidth={} originalHeight={} originalSizeBytes={} processedWidth={} processedHeight={} processedSizeBytes={} mimeType={} compressionQuality={} transformationsApplied={} finalObjectKey={}",
                    accountHolderId(kycCase, metadataJson),
                    documentType,
                    imageProcessing.originalWidth(),
                    imageProcessing.originalHeight(),
                    imageProcessing.originalSizeBytes(),
                    imageProcessing.processedWidth(),
                    imageProcessing.processedHeight(),
                    imageProcessing.processedSizeBytes(),
                    finalMimeType,
                    imageProcessing.compressionQuality(),
                    imageProcessing.transformationsApplied(),
                    objectKey);

            DocumentAnalysisService.DocumentAnalysisResult analysis = null;
            SelfieAnalysisService.SelfieAnalysisResult selfieAnalysis = null;
            if (selfieUpload) {
                selfieAnalysis = selfieAnalysisService.analyze(metadataJson);
                log.info("kyc upload selfie analysis documentType={} mappedProviderType={} fileBytes={} fileName={} mimeType={} validityStatus={} faceDetectedOnSelfie={} selfieQuality={} faceDetectedOnDocument={} faceMatchScore={} faceDecision={} confidenceScore={}",
                        documentType,
                        mappedProviderType,
                        finalBytes.length,
                        resolvedFileName,
                        finalMimeType,
                        selfieAnalysis.validityStatus(),
                        selfieAnalysis.faceDetectedOnSelfie(),
                        selfieAnalysis.selfieQuality(),
                        selfieAnalysis.faceDetectedOnDocument(),
                        selfieAnalysis.faceMatchScore(),
                        selfieAnalysis.faceDecision(),
                        selfieAnalysis.biometricConfidenceScore());
            } else {
                analysis = documentAnalysisService.analyze(documentType, metadataJson);
                log.info("kyc upload analysis documentType={} detectedDocumentType={} supportedDocumentType={} mappedProviderType={} fileBytes={} fileName={} mimeType={} validityStatus={} documentNumberPresent={} expiryDate={} confidenceScore={} ocrMrzReadyObjectKey={} mrzOcrTextRawPresent={} mrzParseSuccess={} documentNumberExtracted={} expiryDateExtracted={} finalExtractionSource={}",
                        documentType,
                        analysis.detectedDocumentType(),
                        analysis.supportedDocumentType(),
                        mappedProviderType,
                        finalBytes.length,
                        resolvedFileName,
                        finalMimeType,
                        analysis.validityStatus(),
                        analysis.documentNumber() != null,
                        analysis.expiryDate(),
                        analysis.confidenceScore(),
                        mrzObjectKey,
                        analysis.mrzDiagnostics().get("mrzOcrTextRawPresent"),
                        analysis.mrzDiagnostics().get("mrzParseSuccess"),
                        analysis.mrzDiagnostics().get("documentNumberExtracted"),
                        analysis.mrzDiagnostics().get("expiryDateExtracted"),
                        analysis.mrzDiagnostics().get("finalExtractionSource"));
            }

            KycDocumentEntity document = new KycDocumentEntity();
            document.setClientId(clientId);
            document.setKycCase(kycCase);
            document.setDocumentType(documentType);
            document.setFileName(resolvedFileName);
            document.setObjectKey(objectKey);
            document.setBucketName(bucketName);
            document.setContentType(finalMimeType);
            document.setSizeBytes(finalBytes.length);
            document.setStatus(KycDocumentStatus.PENDING);
            if (selfieUpload) {
                document.setMetadataJson(enrichedSelfieMetadata(metadataJson, selfieAnalysis, imageProcessing, originalObjectKey, objectKey));
                document.setDocumentNumber(null);
                document.setExpiryDate(null);
                document.setValidityStatus(selfieAnalysis.validityStatus());
            } else {
                document.setMetadataJson(enrichedDocumentMetadata(metadataJson, analysis, imageProcessing, mrzImageProcessing, originalObjectKey, objectKey, mrzObjectKey));
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
            } else {
                caseMetadata.put("identityDocumentId", saved.getId().toString());
                if (analysis != null && analysis.extractedFields() != null) {
                    caseMetadata.put("extractedFields", analysis.extractedFields());
                }
            }
            caseMetadata.put("lastRequestId", RequestCorrelation.requestId());
            kycCase.setMetadataJson(kycFlowService.writeMetadata(caseMetadata));
            kycCaseRepository.save(kycCase);

            KycDocumentEntity actionableDocument = applyImmediateResubmissionDecision(kycCase, saved);
            workflowPublisher.publish(kycCase.getId());
            KycUploadResponse response = toUploadResponse(kycCase, actionableDocument);
            log.info("kyc upload completed requestId={} clientId={} caseId={} documentId={} caseStatus={} productStatus={} reasonCode={} nextAction={} workflowTriggered=true",
                    RequestCorrelation.requestId(),
                    clientId,
                    caseId,
                    saved.getId(),
                    response.caseStatus(),
                    response.productStatus(),
                    response.reasonCode(),
                    response.nextAction());
            return response;
        } catch (Exception exception) {
            log.error("kyc upload failed requestId={} clientId={} caseId={} documentType={} mappedProviderType={} originalFileName={} contentType={} fileSize={} metadataSummary={} failureType={} failureMessage={}",
                    RequestCorrelation.requestId(),
                    clientId,
                    caseId,
                    documentType,
                    mappedProviderType,
                    originalFileName,
                    incomingContentType,
                    incomingFileSize,
                    metadataSummary(metadataJson),
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
        KycDocumentEntity primaryDocument = latestIdentityDocument(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).orElse(null);
        metricsService.recordCaseDecision(kycCase, primaryDocument);
        KycCaseResponse response = toResponse(kycCase);
        log.info("kyc admin review decision requestId={} clientId={} caseId={} caseStatus={} productStatus={} reasonCode={} nextAction={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                response.caseStatus(),
                response.productStatus(),
                response.reasonCode(),
                response.nextAction());
        return response;
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

    private Map<String, Object> metadataSummary(String metadataJson) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("present", metadataJson != null && !metadataJson.isBlank());
        summary.put("length", metadataJson == null ? 0 : metadataJson.length());
        if (metadataJson != null && !metadataJson.isBlank()) {
            summary.put("keys", documentAnalysisService.parse(metadataJson).keySet());
        }
        return summary;
    }

    private String enrichedDocumentMetadata(String metadataJson,
                                            DocumentAnalysisService.DocumentAnalysisResult analysis,
                                            KycImageProcessingService.ImageProcessingResult imageProcessing,
                                            KycImageProcessingService.MrzImageProcessingResult mrzImageProcessing,
                                            String originalObjectKey,
                                            String finalObjectKey,
                                            String mrzObjectKey) {
        Map<String, Object> metadata = new HashMap<>(documentAnalysisService.parse(metadataJson));
        metadata.put("detectedDocumentType", analysis.detectedDocumentType() == null ? null : analysis.detectedDocumentType().name());
        metadata.put("supportedDocumentType", analysis.supportedDocumentType());
        metadata.put("extractedFields", analysis.extractedFields());
        metadata.put("confidenceScore", analysis.confidenceScore());
        metadata.put("imageProcessing", imageProcessingMetadata(imageProcessing, originalObjectKey, finalObjectKey));
        metadata.put("mrzDiagnostics", analysis.mrzDiagnostics());
        if (mrzImageProcessing != null) {
            metadata.put("mrzImageProcessing", mrzImageProcessingMetadata(mrzImageProcessing, mrzObjectKey));
            metadata.put("mrzCropCoordinates", mrzImageProcessing.cropCoordinates());
        }
        return kycFlowService.writeMetadata(metadata);
    }

    private String enrichedSelfieMetadata(String metadataJson,
                                          SelfieAnalysisService.SelfieAnalysisResult analysis,
                                          KycImageProcessingService.ImageProcessingResult imageProcessing,
                                          String originalObjectKey,
                                          String finalObjectKey) {
        Map<String, Object> metadata = new HashMap<>(documentAnalysisService.parse(metadataJson));
        metadata.putAll(analysis.biometricSignals());
        metadata.put("extractedFields", analysis.biometricSignals());
        metadata.put("confidenceScore", analysis.biometricConfidenceScore());
        metadata.put("imageProcessing", imageProcessingMetadata(imageProcessing, originalObjectKey, finalObjectKey));
        return kycFlowService.writeMetadata(metadata);
    }

    private Map<String, Object> imageProcessingMetadata(KycImageProcessingService.ImageProcessingResult imageProcessing,
                                                        String originalObjectKey,
                                                        String finalObjectKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("originalObjectKey", originalObjectKey);
        metadata.put("finalObjectKey", finalObjectKey);
        metadata.put("originalWidth", imageProcessing.originalWidth());
        metadata.put("originalHeight", imageProcessing.originalHeight());
        metadata.put("originalSizeBytes", imageProcessing.originalSizeBytes());
        metadata.put("processedWidth", imageProcessing.processedWidth());
        metadata.put("processedHeight", imageProcessing.processedHeight());
        metadata.put("processedSizeBytes", imageProcessing.processedSizeBytes());
        metadata.put("mimeType", imageProcessing.mimeType());
        metadata.put("compressionQuality", imageProcessing.compressionQuality());
        metadata.put("transformationsApplied", imageProcessing.transformationsApplied());
        return metadata;
    }

    private Map<String, Object> mrzImageProcessingMetadata(KycImageProcessingService.MrzImageProcessingResult mrzImageProcessing,
                                                           String mrzObjectKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("processed", mrzImageProcessing.processed());
        metadata.put("objectKey", mrzObjectKey);
        metadata.put("cropCoordinates", mrzImageProcessing.cropCoordinates());
        metadata.put("cropX", mrzImageProcessing.cropX());
        metadata.put("cropY", mrzImageProcessing.cropY());
        metadata.put("cropWidth", mrzImageProcessing.cropWidth());
        metadata.put("cropHeight", mrzImageProcessing.cropHeight());
        metadata.put("processedSizeBytes", mrzImageProcessing.processedBytes().length);
        metadata.put("processedMimeType", mrzImageProcessing.processedMimeType());
        metadata.put("transformationsApplied", mrzImageProcessing.transformationsApplied());
        return metadata;
    }

    private String objectKey(UUID clientId, String referenceId, String slot, long timestamp, String fileName) {
        return clientId + "/" + referenceId + "/" + slot + "/" + timestamp + "-" + fileName;
    }

    private void uploadToS3(String objectKey, byte[] bytes, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .build();
        log.info("kyc upload storage start provider=S3 endpoint={} bucket={} objectKey={} contentType={} size={}",
                s3Endpoint,
                bucketName,
                objectKey,
                contentType,
                bytes.length);
        try {
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(bytes));
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
                bytes.length);
    }

    private String accountHolderId(KycCaseEntity kycCase, String metadataJson) {
        Object accountHolderId = documentAnalysisService.parse(metadataJson).get("accountHolderId");
        if (accountHolderId != null && !String.valueOf(accountHolderId).isBlank()) {
            return String.valueOf(accountHolderId);
        }
        if (kycCase.getUserId() != null && !kycCase.getUserId().isBlank()) {
            return kycCase.getUserId();
        }
        return kycCase.getIdentity() == null ? null : String.valueOf(kycCase.getIdentity().getId());
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
        metadata.put("lastRequestId", RequestCorrelation.requestId());

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
        if (!documentsReadyForDecision(documents)) {
            log.info("kyc upload resubmission deferred requestId={} clientId={} caseId={} documentId={} documentType={} caseStatus={} workflowDecision={}",
                    RequestCorrelation.requestId(),
                    kycCase.getClientId(),
                    kycCase.getId(),
                    uploadedDocument.getId(),
                    uploadedDocument.getDocumentType(),
                    kycCase.getStatus(),
                    KycFlowService.WORKFLOW_WAITING_FOR_DOCUMENTS);
            return uploadedDocument;
        }

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

    private boolean documentsReadyForDecision(List<KycDocumentEntity> documents) {
        boolean hasIdentityFront = false;
        boolean hasIdentityBack = false;
        boolean requiresIdentityBack = false;
        boolean hasSelfie = false;
        int unknownIdentityDocuments = 0;

        for (KycDocumentEntity document : documents) {
            if (document.getDocumentType() == KycDocumentType.SELFIE) {
                hasSelfie = true;
                continue;
            }
            if (!isIdentityDocument(document)) {
                continue;
            }
            requiresIdentityBack = requiresIdentityBack || requiresBack(document);
            String slot = documentSlot(document);
            if ("IDENTITY_BACK".equals(slot)) {
                hasIdentityBack = true;
            } else if ("IDENTITY_FRONT".equals(slot)) {
                hasIdentityFront = true;
            } else {
                unknownIdentityDocuments++;
                if (unknownIdentityDocuments == 1) {
                    hasIdentityFront = true;
                } else {
                    hasIdentityBack = true;
                }
            }
        }

        return hasIdentityFront && hasSelfie && (!requiresIdentityBack || hasIdentityBack);
    }

    private boolean requiresBack(KycDocumentEntity document) {
        KycDocumentType documentType = document.getDocumentType();
        boolean twoSidedType = documentType == KycDocumentType.ID_CARD
                || documentType == KycDocumentType.NATIONAL_ID
                || documentType == KycDocumentType.VOTER_CARD
                || documentType == KycDocumentType.DRIVER_LICENSE;
        if (!twoSidedType) {
            return false;
        }
        Map<String, Object> metadata = documentMetadata(document);
        return "IDENTITY_FRONT".equals(documentSlot(document))
                || "IDENTITY_BACK".equals(documentSlot(document))
                || asBoolean(metadata.get("requiresBack"))
                || asBoolean(metadata.get("requiresIdentityBack"))
                || asBoolean(metadata.get("backRequired"));
    }

    private String documentSlot(KycDocumentEntity document) {
        Map<String, Object> metadata = documentMetadata(document);
        String rawSlot = blankToNull(firstNonBlank(
                firstNonBlank(asString(metadata.get("documentSlot")), asString(metadata.get("documentSide"))),
                firstNonBlank(asString(metadata.get("side")), asString(metadata.get("slot")))
        ));
        if (rawSlot == null) {
            return null;
        }
        String normalized = rawSlot.toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (normalized.contains("BACK") || normalized.contains("VERSO")) {
            return "IDENTITY_BACK";
        }
        if (normalized.contains("FRONT") || normalized.contains("RECTO")) {
            return "IDENTITY_FRONT";
        }
        return null;
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

    private List<String> documentDiagnostics(List<KycDocumentEntity> documents) {
        return documents.stream()
                .limit(20)
                .map(document -> document.getId()
                        + ":case=" + (document.getKycCase() == null ? null : document.getKycCase().getId())
                        + ":type=" + document.getDocumentType()
                        + ":status=" + document.getStatus()
                        + ":validity=" + document.getValidityStatus()
                        + ":createdAt=" + document.getCreatedAt()
                        + ":objectKey=" + document.getObjectKey())
                .toList();
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
            if (normalizedReason.contains("biometric") || normalizedReason.contains("face match")) {
                return KycReasonCode.BIOMETRIC_MISMATCH;
            }
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
        if (reasonCode == KycReasonCode.BIOMETRIC_MISMATCH) {
            return KycNextAction.RETRY_SELFIE_CAPTURE;
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
        if (reasonCode == KycReasonCode.BIOMETRIC_MISMATCH) {
            return KycFlowService.REASON_BIOMETRIC_MISMATCH;
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
        if (reasonCode == KycReasonCode.BIOMETRIC_MISMATCH) {
            return "Ask the user to retake the selfie or restart with matching identity evidence.";
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

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String asString(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
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
