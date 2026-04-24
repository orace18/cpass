package com.cpass.platform.kyc.service;

import com.cpass.platform.common.security.RequestCorrelation;
import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.IdentityKycEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycReasonCode;
import com.cpass.platform.kyc.domain.KycStepType;
import com.cpass.platform.kyc.infra.IdentityKycRepository;
import com.cpass.platform.kyc.infra.IdentityRepository;
import com.cpass.platform.kyc.infra.KycCaseRepository;
import com.cpass.platform.kyc.infra.KycDocumentRepository;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderType;
import com.cpass.platform.provider.infra.ProviderConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KycFlowService {

    private static final Logger log = LoggerFactory.getLogger(KycFlowService.class);

    static final String REASON_DOCUMENT_UNREADABLE = "Document is unreadable. Please resubmit a clearer image.";
    static final String REASON_SELFIE_UNREADABLE = "Selfie is unreadable. Please retry capture.";
    static final String REASON_DOCUMENT_EXPIRED = "Document is expired.";
    static final String REASON_DECLARED_INFO_MISMATCH = "Declared identity information does not match extracted document information.";
    static final String REASON_UNSUPPORTED_DOCUMENT_TYPE = "Unsupported document type for identity verification.";
    static final String REASON_INSUFFICIENT_DOCUMENT_SIGNALS = "Document signals are insufficient for automatic verification.";
    static final String REASON_BIOMETRIC_REVIEW_REQUIRED = "Biometric face match is inconclusive. Manual review required.";
    static final String REASON_BIOMETRIC_MISMATCH = "Biometric face match does not match the identity document. Please retry selfie capture.";
    static final String REASON_MISSING_REQUIRED_DOCUMENT = "Required identity document is missing.";
    static final String WORKFLOW_WAITING_FOR_DOCUMENTS = "WAITING_FOR_DOCUMENTS";
    static final String WORKFLOW_READY_FOR_DECISION = "READY_FOR_DECISION";
    private static final String SLOT_IDENTITY_FRONT = "IDENTITY_FRONT";
    private static final String SLOT_IDENTITY_BACK = "IDENTITY_BACK";
    private static final String SLOT_SELFIE = "SELFIE";

    private final KycCaseRepository kycCaseRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final IdentityRepository identityRepository;
    private final IdentityKycRepository identityKycRepository;
    private final DocumentAnalysisService documentAnalysisService;
    private final SelfieAnalysisService selfieAnalysisService;
    private final ProviderConfigRepository providerConfigRepository;
    private final Map<String, KycProviderAdapter> providersByCode;
    private final KycFlowProperties properties;
    private final KycOperationalMetricsService metricsService;
    private final ObjectMapper objectMapper;

    public KycFlowService(KycCaseRepository kycCaseRepository,
                          KycDocumentRepository kycDocumentRepository,
                          IdentityRepository identityRepository,
                          IdentityKycRepository identityKycRepository,
                          DocumentAnalysisService documentAnalysisService,
                          SelfieAnalysisService selfieAnalysisService,
                          ProviderConfigRepository providerConfigRepository,
                          List<KycProviderAdapter> providers,
                          KycFlowProperties properties,
                          KycOperationalMetricsService metricsService,
                          ObjectMapper objectMapper) {
        this.kycCaseRepository = kycCaseRepository;
        this.kycDocumentRepository = kycDocumentRepository;
        this.identityRepository = identityRepository;
        this.identityKycRepository = identityKycRepository;
        this.documentAnalysisService = documentAnalysisService;
        this.selfieAnalysisService = selfieAnalysisService;
        this.providerConfigRepository = providerConfigRepository;
        this.providersByCode = providers.stream().collect(Collectors.toMap(KycProviderAdapter::providerCode, Function.identity()));
        this.properties = properties;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(java.util.UUID caseId) {
        log.info("kyc workflow process started caseId={}", caseId);
        Optional<KycCaseEntity> optionalCase = kycCaseRepository.findById(caseId);
        if (optionalCase.isEmpty()) {
            log.warn("kyc workflow aborted reason=case_not_found caseId={}", caseId);
            return;
        }

        KycCaseEntity kycCase = optionalCase.get();
        List<KycStepType> steps = properties.stepsFor(kycCase.getLevel());
        Map<String, Object> metadata = readMetadata(kycCase.getMetadataJson());
        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
        log.info("kyc workflow case loaded requestId={} clientId={} caseId={} status={} level={} createdAt={} updatedAt={} metadataKeys={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                kycCase.getStatus(),
                kycCase.getLevel(),
                kycCase.getCreatedAt(),
                kycCase.getUpdatedAt(),
                metadata.keySet());
        log.info("kyc workflow docs found requestId={} clientId={} caseId={} count={} documents={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                documents.size(),
                documents.stream()
                        .map(document -> document.getId() + ":" + document.getDocumentType() + ":" + document.getStatus() + ":" + document.getValidityStatus())
                        .toList());

        DocumentReadiness documentReadiness = documentReadiness(steps, documents);
        log.info("kyc workflow document readiness requestId={} clientId={} caseId={} requiredDocuments={} receivedDocuments={} missingDocuments={} workflowDecision={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                documentReadiness.requiredDocuments(),
                documentReadiness.receivedDocuments(),
                documentReadiness.missingDocuments(),
                documentReadiness.ready() ? WORKFLOW_READY_FOR_DECISION : WORKFLOW_WAITING_FOR_DOCUMENTS);
        if (!documentReadiness.ready()) {
            log.info("kyc workflow no-op requestId={} clientId={} caseId={} reason={} requiredDocuments={} receivedDocuments={} missingDocuments={} workflowDecision={}",
                    RequestCorrelation.requestId(),
                    kycCase.getClientId(),
                    kycCase.getId(),
                    documents.isEmpty() ? "no_documents_found" : "missing_required_documents",
                    documentReadiness.requiredDocuments(),
                    documentReadiness.receivedDocuments(),
                    documentReadiness.missingDocuments(),
                    WORKFLOW_WAITING_FOR_DOCUMENTS);
            return;
        }

        ensureDocumentsAnalyzed(kycCase, documents);

        KycDocumentEntity primaryDocument = latestIdentityDocument(documents);
        KycDocumentEntity usableIdentityDocument = latestUsableIdentityDocument(documents);
        KycDocumentEntity unsupportedIdentityDocument = latestUnsupportedIdentityDocument(documents);
        KycDocumentEntity unreadableIdentityDocument = latestUnreadableIdentityDocument(documents);
        KycDocumentEntity selfieDocument = latestDocumentOfType(documents, KycDocumentType.SELFIE);
        SelfieAnalysisService.SelfieAnalysisResult selfieAnalysis = selfieDocument == null
                ? null
                : selfieAnalysisService.analyze(selfieDocument.getMetadataJson());

        KycCaseStatus status = KycCaseStatus.INITIATED;
        String reason = null;
        List<Integer> signals = new ArrayList<>();

        if (selfieAnalysis != null && selfieAnalysis.validityStatus() == KycDocumentValidityStatus.UNREADABLE) {
            log.info("kyc workflow aborted reason=selfie_unreadable caseId={}", caseId);
            persist(kycCase, KycCaseStatus.RESUBMISSION_REQUIRED, List.of(selfieAnalysis.biometricConfidenceScore()), REASON_SELFIE_UNREADABLE, null, selfieDocument);
            return;
        }

        if (requiresIdentityChecks(steps) && unsupportedIdentityDocument != null && usableIdentityDocument == null) {
            log.info("kyc workflow aborted reason=unsupported_document caseId={}", caseId);
            persist(kycCase, KycCaseStatus.RESUBMISSION_REQUIRED, List.of(20), REASON_UNSUPPORTED_DOCUMENT_TYPE, null, unsupportedIdentityDocument);
            return;
        }

        if (requiresIdentityChecks(steps) && unreadableIdentityDocument != null && usableIdentityDocument == null) {
            log.info("kyc workflow aborted reason=document_unreadable caseId={}", caseId);
            persist(kycCase, KycCaseStatus.RESUBMISSION_REQUIRED, List.of(30), REASON_DOCUMENT_UNREADABLE, null, unreadableIdentityDocument);
            return;
        }

        if (requiresIdentityChecks(steps)) {
            primaryDocument = usableIdentityDocument == null ? primaryDocument : usableIdentityDocument;
            if (primaryDocument == null) {
                log.info("kyc workflow aborted reason=missing_primary_document caseId={}", caseId);
                persist(kycCase, KycCaseStatus.REVIEW, signals, REASON_MISSING_REQUIRED_DOCUMENT, null, null);
                return;
            }
            if (isUnsupportedDocumentType(primaryDocument)) {
                persist(kycCase, KycCaseStatus.RESUBMISSION_REQUIRED, List.of(20), REASON_UNSUPPORTED_DOCUMENT_TYPE, null, primaryDocument);
                return;
            }
            if (primaryDocument.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE) {
                persist(kycCase, KycCaseStatus.RESUBMISSION_REQUIRED, List.of(30), REASON_DOCUMENT_UNREADABLE, null, primaryDocument);
                return;
            }
            if (primaryDocument.getValidityStatus() == KycDocumentValidityStatus.EXPIRED) {
                log.info("kyc workflow aborted reason=document_expired caseId={}", caseId);
                persist(kycCase, KycCaseStatus.REJECTED, List.of(20), REASON_DOCUMENT_EXPIRED, null, primaryDocument);
                return;
            }
            if (hasDeclaredInfoMismatch(primaryDocument, kycCase.getIdentity(), metadata)) {
                log.info("kyc workflow aborted reason=declared_info_mismatch caseId={}", caseId);
                persist(kycCase, KycCaseStatus.RESUBMISSION_REQUIRED, List.of(45), REASON_DECLARED_INFO_MISMATCH, null, primaryDocument);
                return;
            }
            if (hasInsufficientIdentitySignals(primaryDocument, metadata)) {
                persist(kycCase, KycCaseStatus.REVIEW, List.of(confidenceScore(primaryDocument)), REASON_INSUFFICIENT_DOCUMENT_SIGNALS, null, primaryDocument);
                return;
            }
            status = KycCaseStatus.IDENTITY_PRECHECKED;
            signals.add(confidenceScore(primaryDocument));
            syncIdentity(primaryDocument, kycCase.getIdentity(), metadata);
        }

        if (steps.contains(KycStepType.FACE_MATCH)) {
            if (selfieAnalysis == null) {
                log.info("kyc workflow aborted reason=missing_selfie_analysis caseId={}", caseId);
                persist(kycCase, KycCaseStatus.REVIEW, signals, REASON_BIOMETRIC_REVIEW_REQUIRED, null, primaryDocument);
                return;
            }
            if (SelfieAnalysisService.FACE_DECISION_MATCH.equals(selfieAnalysis.faceDecision())) {
                signals.add(selfieAnalysis.biometricConfidenceScore());
            } else if (SelfieAnalysisService.FACE_DECISION_MISMATCH.equals(selfieAnalysis.faceDecision())) {
                log.info("kyc workflow aborted reason=biometric_mismatch caseId={}", caseId);
                persist(kycCase, KycCaseStatus.RESUBMISSION_REQUIRED, signalsWithBiometric(signals, selfieAnalysis), REASON_BIOMETRIC_MISMATCH, null, selfieDocument);
                return;
            } else {
                log.info("kyc workflow aborted reason=biometric_review_required caseId={}", caseId);
                persist(kycCase, KycCaseStatus.REVIEW, signalsWithBiometric(signals, selfieAnalysis), REASON_BIOMETRIC_REVIEW_REQUIRED, null, primaryDocument);
                return;
            }
        }

        if (steps.contains(KycStepType.FIELD_VERIFICATION)) {
            if (!metadata.containsKey("fieldVerification")) {
                persist(kycCase, status, signals, reason, null, primaryDocument);
                return;
            }
            status = KycCaseStatus.FIELD_VERIFIED;
            signals.add(90);
        }

        if (steps.contains(KycStepType.SOCIAL_GUARANTEE)) {
            if (!metadata.containsKey("socialGuarantee")) {
                persist(kycCase, status, signals, reason, null, primaryDocument);
                return;
            }
            status = KycCaseStatus.SOCIAL_VALIDATED;
            signals.add(90);
        }

        int score = signals.isEmpty() ? 0 : (int) Math.round(signals.stream().mapToInt(Integer::intValue).average().orElse(0));

        if (steps.contains(KycStepType.EXTERNAL_PROVIDER_FALLBACK) && score < properties.fallbackScoreThreshold()) {
            KycProviderDecision providerDecision = callExternalProvider(kycCase, score);
            if (providerDecision != null) {
                if (providerDecision.status() != KycCaseStatus.APPROVED) {
                    persist(kycCase, providerDecision.status(), List.of(providerDecision.score()), providerDecision.reason(), null, primaryDocument);
                    return;
                }
                score = providerDecision.score();
                reason = providerDecision.reason();
            }
        }

        if (score >= properties.autoApproveScoreThreshold()) {
            persist(kycCase, KycCaseStatus.APPROVED, List.of(score), reason, Instant.now().plus(properties.validityPeriod()), primaryDocument);
            return;
        }

        persist(kycCase, KycCaseStatus.REVIEW, List.of(score), reason == null ? "Manual review required" : reason, null, primaryDocument);
    }

    private void persist(KycCaseEntity kycCase,
                         KycCaseStatus status,
                         List<Integer> signals,
                         String reason,
                         Instant validUntil,
                         KycDocumentEntity primaryDocument) {
        int score = signals.isEmpty() ? 0 : (int) Math.round(signals.stream().mapToInt(Integer::intValue).average().orElse(0));
        kycCase.setStatus(status);
        kycCase.setScore(score);
        kycCase.setStatusReason(reason);
        kycCase.setValidUntil(validUntil);
        kycCase.setLastProcessedAt(Instant.now());
        if (primaryDocument != null) {
            if (isIdentityDocument(primaryDocument)) {
                kycCase.setDocumentExpiryDate(primaryDocument.getExpiryDate());
            }
            if (status == KycCaseStatus.APPROVED) {
                primaryDocument.setStatus(KycDocumentStatus.APPROVED);
                primaryDocument.setReviewedAt(Instant.now());
                kycDocumentRepository.save(primaryDocument);
            } else if (status == KycCaseStatus.REJECTED) {
                primaryDocument.setStatus(KycDocumentStatus.REJECTED);
                primaryDocument.setReviewReason(reason);
                primaryDocument.setReviewedAt(Instant.now());
                kycDocumentRepository.save(primaryDocument);
            } else if (status == KycCaseStatus.RESUBMISSION_REQUIRED) {
                primaryDocument.setStatus(KycDocumentStatus.RESUBMISSION_REQUIRED);
                primaryDocument.setReviewReason(reason);
                primaryDocument.setReviewedAt(Instant.now());
                kycDocumentRepository.save(primaryDocument);
            }
        }
        kycCaseRepository.save(kycCase);
        syncMapping(kycCase);
        KycReasonCode reasonCode = metricsService.reasonCode(kycCase, primaryDocument);
        metricsService.recordCaseDecision(kycCase, primaryDocument);
        log.info("kyc workflow final status persisted requestId={} clientId={} caseId={} status={} score={} reason={} validUntil={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                status,
                score,
                reason,
                validUntil);
        log.info("kyc workflow decision requestId={} clientId={} caseId={} status={} productStatus={} score={} reasonCode={} reason={} documentId={} documentStatus={} validityStatus={} processingMillis={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                status,
                productStatus(status),
                score,
                reasonCode,
                reason,
                primaryDocument == null ? null : primaryDocument.getId(),
                primaryDocument == null ? null : primaryDocument.getStatus(),
                primaryDocument == null ? null : primaryDocument.getValidityStatus(),
                kycCase.getCreatedAt() == null || kycCase.getLastProcessedAt() == null
                        ? null
                        : java.time.Duration.between(kycCase.getCreatedAt(), kycCase.getLastProcessedAt()).toMillis());
    }

    private void ensureDocumentsAnalyzed(KycCaseEntity kycCase, List<KycDocumentEntity> documents) {
        for (KycDocumentEntity document : documents) {
            if (document.getDocumentType() == KycDocumentType.SELFIE) {
                ensureSelfieAnalyzed(kycCase, document);
            } else if (isIdentityDocument(document)) {
                ensureIdentityDocumentAnalyzed(kycCase, document);
            }
        }
    }

    private void ensureIdentityDocumentAnalyzed(KycCaseEntity kycCase, KycDocumentEntity document) {
        Map<String, Object> metadata = documentMetadata(document);
        if (document.getValidityStatus() != null) {
            log.info("kyc workflow document analysis skipped requestId={} clientId={} caseId={} documentId={} reason=already_enriched",
                    RequestCorrelation.requestId(),
                    kycCase.getClientId(),
                    kycCase.getId(),
                    document.getId());
            return;
        }

        log.info("kyc workflow document analysis launched requestId={} clientId={} caseId={} documentId={} documentType={} metadataKeys={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                document.getId(),
                document.getDocumentType(),
                metadata.keySet());
        DocumentAnalysisService.DocumentAnalysisResult analysis = documentAnalysisService.analyze(document.getDocumentType(), document.getMetadataJson());
        Map<String, Object> enriched = new HashMap<>(metadata);
        enriched.put("detectedDocumentType", analysis.detectedDocumentType() == null ? null : analysis.detectedDocumentType().name());
        enriched.put("supportedDocumentType", analysis.supportedDocumentType());
        enriched.put("extractedFields", analysis.extractedFields());
        enriched.put("confidenceScore", analysis.confidenceScore());
        document.setMetadataJson(writeMetadata(enriched));
        document.setDocumentNumber(analysis.documentNumber());
        document.setExpiryDate(analysis.expiryDate());
        document.setValidityStatus(analysis.validityStatus());
        kycDocumentRepository.save(document);
        log.info("kyc workflow document analysis result requestId={} clientId={} caseId={} documentId={} validityStatus={} confidenceScore={} documentNumberPresent={} expiryDate={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                document.getId(),
                analysis.validityStatus(),
                analysis.confidenceScore(),
                analysis.documentNumber() != null,
                analysis.expiryDate());
    }

    private void ensureSelfieAnalyzed(KycCaseEntity kycCase, KycDocumentEntity document) {
        Map<String, Object> metadata = documentMetadata(document);
        if (document.getValidityStatus() != null) {
            log.info("kyc workflow selfie analysis skipped requestId={} clientId={} caseId={} documentId={} reason=already_enriched",
                    RequestCorrelation.requestId(),
                    kycCase.getClientId(),
                    kycCase.getId(),
                    document.getId());
            return;
        }

        log.info("kyc workflow selfie analysis launched requestId={} clientId={} caseId={} documentId={} metadataKeys={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                document.getId(),
                metadata.keySet());
        SelfieAnalysisService.SelfieAnalysisResult analysis = selfieAnalysisService.analyze(document.getMetadataJson());
        Map<String, Object> enriched = new HashMap<>(metadata);
        enriched.putAll(analysis.biometricSignals());
        enriched.put("extractedFields", analysis.biometricSignals());
        enriched.put("confidenceScore", analysis.biometricConfidenceScore());
        document.setMetadataJson(writeMetadata(enriched));
        document.setValidityStatus(analysis.validityStatus());
        kycDocumentRepository.save(document);
        log.info("kyc workflow selfie analysis result requestId={} clientId={} caseId={} documentId={} validityStatus={} faceDecision={} confidenceScore={}",
                RequestCorrelation.requestId(),
                kycCase.getClientId(),
                kycCase.getId(),
                document.getId(),
                analysis.validityStatus(),
                analysis.faceDecision(),
                analysis.biometricConfidenceScore());
    }

    private String productStatus(KycCaseStatus status) {
        return status == KycCaseStatus.APPROVED ? "VERIFIED" : status.name();
    }

    private void syncIdentity(KycDocumentEntity primaryDocument, IdentityEntity identity, Map<String, Object> metadata) {
        if (primaryDocument.getDocumentNumber() != null && (identity.getDocumentNumber() == null || identity.getDocumentNumber().isBlank())) {
            identity.setDocumentNumber(primaryDocument.getDocumentNumber());
        }
        Map<String, Object> extractedFields = extractedFields(primaryDocument);
        if (identity.getFaceHash() == null) {
            Object faceHash = metadata.get("faceHash");
            if (faceHash != null) {
                identity.setFaceHash(String.valueOf(faceHash));
            }
        }
        if (identity.getPhoneNumber() == null) {
            Object phoneNumber = metadata.get("phoneNumber");
            if (phoneNumber != null) {
                identity.setPhoneNumber(String.valueOf(phoneNumber));
            }
        }
        if (identity.getFullName() == null) {
            Object fullName = firstNonBlank(asString(extractedFields.get("fullName")), asString(metadata.get("fullName")));
            if (fullName != null) {
                identity.setFullName(String.valueOf(fullName));
            }
        }
        identityRepository.save(identity);
    }

    private void syncMapping(KycCaseEntity kycCase) {
        identityKycRepository.findByKycCase(kycCase).ifPresent(mapping -> {
            mapping.setLevel(kycCase.getLevel());
            mapping.setStatus(kycCase.getStatus());
            mapping.setValidUntil(kycCase.getValidUntil());
            identityKycRepository.save(mapping);
        });
    }

    private KycProviderDecision callExternalProvider(KycCaseEntity kycCase, int currentScore) {
        return providerConfigRepository.findByProviderTypeAndActiveTrueOrderByPriorityAsc(ProviderType.KYC).stream()
                .map(config -> {
                    KycProviderAdapter adapter = providersByCode.get(config.getCode());
                    return adapter == null ? null : adapter.review(kycCase, currentScore, config);
                })
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<Integer> signalsWithBiometric(List<Integer> signals, SelfieAnalysisService.SelfieAnalysisResult selfieAnalysis) {
        List<Integer> enrichedSignals = new ArrayList<>(signals);
        if (selfieAnalysis != null && selfieAnalysis.biometricConfidenceScore() != null) {
            enrichedSignals.add(selfieAnalysis.biometricConfidenceScore());
        }
        return enrichedSignals;
    }

    private boolean requiresIdentityChecks(List<KycStepType> steps) {
        return steps.contains(KycStepType.ID_UPLOAD)
                || steps.contains(KycStepType.OCR_EXTRACTION)
                || steps.contains(KycStepType.DOCUMENT_EXPIRY_CHECK);
    }

    private DocumentReadiness documentReadiness(List<KycStepType> steps, List<KycDocumentEntity> documents) {
        Set<String> requiredDocuments = new LinkedHashSet<>();
        if (requiresIdentityChecks(steps)) {
            requiredDocuments.add(SLOT_IDENTITY_FRONT);
            requiredBackDocumentType(documents).ifPresent(documentType -> requiredDocuments.add(SLOT_IDENTITY_BACK));
        }
        if (steps.contains(KycStepType.FACE_MATCH)) {
            requiredDocuments.add(SLOT_SELFIE);
        }

        Set<String> receivedDocuments = receivedDocuments(documents);
        Set<String> missingDocuments = new LinkedHashSet<>(requiredDocuments);
        missingDocuments.removeAll(receivedDocuments);
        return new DocumentReadiness(
                List.copyOf(requiredDocuments),
                List.copyOf(receivedDocuments),
                List.copyOf(missingDocuments)
        );
    }

    private Optional<KycDocumentType> requiredBackDocumentType(List<KycDocumentEntity> documents) {
        return documents.stream()
                .filter(this::isIdentityDocument)
                .filter(this::requiresBack)
                .map(KycDocumentEntity::getDocumentType)
                .findFirst();
    }

    private Set<String> receivedDocuments(List<KycDocumentEntity> documents) {
        Set<String> receivedDocuments = new LinkedHashSet<>();
        int unknownIdentityDocuments = 0;
        for (KycDocumentEntity document : documents) {
            if (document.getDocumentType() == KycDocumentType.SELFIE) {
                receivedDocuments.add(SLOT_SELFIE);
                continue;
            }
            if (!isIdentityDocument(document)) {
                continue;
            }
            String slot = documentSlot(document);
            if (SLOT_IDENTITY_BACK.equals(slot)) {
                receivedDocuments.add(SLOT_IDENTITY_BACK);
            } else if (SLOT_IDENTITY_FRONT.equals(slot)) {
                receivedDocuments.add(SLOT_IDENTITY_FRONT);
            } else {
                unknownIdentityDocuments++;
                receivedDocuments.add(unknownIdentityDocuments == 1 ? SLOT_IDENTITY_FRONT : SLOT_IDENTITY_BACK);
            }
        }
        return receivedDocuments;
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
        return SLOT_IDENTITY_FRONT.equals(documentSlot(document))
                || SLOT_IDENTITY_BACK.equals(documentSlot(document))
                || asBoolean(metadata.get("requiresBack"))
                || asBoolean(metadata.get("requiresIdentityBack"))
                || asBoolean(metadata.get("backRequired"));
    }

    private String documentSlot(KycDocumentEntity document) {
        Map<String, Object> metadata = documentMetadata(document);
        String rawSlot = firstNonBlank(
                firstNonBlank(asString(metadata.get("documentSlot")), asString(metadata.get("documentSide"))),
                firstNonBlank(asString(metadata.get("side")), asString(metadata.get("slot")))
        );
        if (rawSlot == null) {
            return null;
        }
        String normalized = rawSlot.toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (normalized.contains("BACK") || normalized.contains("VERSO")) {
            return SLOT_IDENTITY_BACK;
        }
        if (normalized.contains("FRONT") || normalized.contains("RECTO")) {
            return SLOT_IDENTITY_FRONT;
        }
        return null;
    }

    private KycDocumentEntity latestIdentityDocument(List<KycDocumentEntity> documents) {
        KycDocumentEntity preferred = documents.stream()
                .filter(this::isIdentityDocument)
                .filter(document -> !SLOT_IDENTITY_BACK.equals(documentSlot(document)))
                .reduce((first, second) -> second)
                .orElse(null);
        return preferred == null
                ? documents.stream()
                .filter(this::isIdentityDocument)
                .reduce((first, second) -> second)
                .orElse(null)
                : preferred;
    }

    private KycDocumentEntity latestUsableIdentityDocument(List<KycDocumentEntity> documents) {
        KycDocumentEntity preferred = documents.stream()
                .filter(this::isIdentityDocument)
                .filter(document -> !SLOT_IDENTITY_BACK.equals(documentSlot(document)))
                .filter(document -> document.getValidityStatus() == KycDocumentValidityStatus.VALID)
                .filter(document -> !isUnsupportedDocumentType(document))
                .reduce((first, second) -> second)
                .orElse(null);
        return preferred == null
                ? documents.stream()
                .filter(this::isIdentityDocument)
                .filter(document -> document.getValidityStatus() == KycDocumentValidityStatus.VALID)
                .filter(document -> !isUnsupportedDocumentType(document))
                .reduce((first, second) -> second)
                .orElse(null)
                : preferred;
    }

    private KycDocumentEntity latestUnsupportedIdentityDocument(List<KycDocumentEntity> documents) {
        return documents.stream()
                .filter(this::isIdentityDocument)
                .filter(this::isUnsupportedDocumentType)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private KycDocumentEntity latestUnreadableIdentityDocument(List<KycDocumentEntity> documents) {
        return documents.stream()
                .filter(this::isIdentityDocument)
                .filter(document -> document.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private KycDocumentEntity latestDocumentOfType(List<KycDocumentEntity> documents, KycDocumentType documentType) {
        return documents.stream()
                .filter(document -> document.getDocumentType() == documentType)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private boolean isIdentityDocument(KycDocumentEntity document) {
        return document.getDocumentType() != KycDocumentType.SELFIE
                && document.getDocumentType() != KycDocumentType.FIELD_AGENT_PHOTO;
    }

    private boolean hasDeclaredInfoMismatch(KycDocumentEntity primaryDocument,
                                            IdentityEntity identity,
                                            Map<String, Object> metadata) {
        Map<String, Object> extractedFields = extractedFields(primaryDocument);

        String declaredDocumentNumber = firstNonBlank(identity.getDocumentNumber(), asString(metadata.get("documentNumber")));
        String extractedDocumentNumber = asString(primaryDocument.getDocumentNumber());
        if (declaredDocumentNumber != null
                && extractedDocumentNumber != null
                && !normalizeIdentifier(declaredDocumentNumber).equals(normalizeIdentifier(extractedDocumentNumber))) {
            return true;
        }

        String declaredFullName = firstNonBlank(identity.getFullName(), asString(metadata.get("fullName")));
        String extractedFullName = asString(extractedFields.get("fullName"));
        if (declaredFullName != null && extractedFullName != null && !namesMatch(declaredFullName, extractedFullName)) {
            return true;
        }

        LocalDate declaredBirthDate = asLocalDate(metadata.get("birthDate"));
        LocalDate extractedBirthDate = asLocalDate(extractedFields.get("birthDate"));
        return declaredBirthDate != null
                && extractedBirthDate != null
                && !declaredBirthDate.equals(extractedBirthDate);
    }

    private boolean hasInsufficientIdentitySignals(KycDocumentEntity primaryDocument, Map<String, Object> metadata) {
        Map<String, Object> extractedFields = extractedFields(primaryDocument);
        boolean hasDocumentNumber = primaryDocument.getDocumentNumber() != null;
        boolean hasExpiryDate = primaryDocument.getExpiryDate() != null;
        boolean hasFullName = asString(extractedFields.get("fullName")) != null;
        boolean declaredBirthDateRequiresExtraction = asLocalDate(metadata.get("birthDate")) != null;
        boolean hasBirthDate = asLocalDate(extractedFields.get("birthDate")) != null;
        return !hasDocumentNumber
                || !hasExpiryDate
                || !hasFullName
                || (declaredBirthDateRequiresExtraction && !hasBirthDate);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractedFields(KycDocumentEntity document) {
        Object fields = documentMetadata(document).get("extractedFields");
        if (fields instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private Integer confidenceScore(KycDocumentEntity document) {
        Integer confidenceScore = asInteger(documentMetadata(document).get("confidenceScore"));
        return confidenceScore == null ? 75 : confidenceScore;
    }

    private boolean isUnsupportedDocumentType(KycDocumentEntity document) {
        Object supported = documentMetadata(document).get("supportedDocumentType");
        return supported instanceof Boolean bool && !bool;
    }

    private Map<String, Object> documentMetadata(KycDocumentEntity document) {
        return documentAnalysisService.parse(document.getMetadataJson());
    }

    private LocalDate asLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        String raw = asString(value);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean namesMatch(String declaredFullName, String extractedFullName) {
        String[] declaredTokens = normalizeName(declaredFullName).split(" ");
        List<String> extractedTokens = Arrays.asList(normalizeName(extractedFullName).split(" "));
        return Arrays.stream(declaredTokens)
                .filter(token -> !token.isBlank())
                .allMatch(extractedTokens::contains);
    }

    private String normalizeName(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private String normalizeIdentifier(String value) {
        return value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return new HashMap<>();
        }
    }

    public String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize KYC metadata", exception);
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
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

    private String asString(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private record DocumentReadiness(List<String> requiredDocuments,
                                     List<String> receivedDocuments,
                                     List<String> missingDocuments) {
        private boolean ready() {
            return missingDocuments.isEmpty();
        }
    }
}
