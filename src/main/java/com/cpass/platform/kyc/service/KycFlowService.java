package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.IdentityKycEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KycFlowService {

    private final KycCaseRepository kycCaseRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final IdentityRepository identityRepository;
    private final IdentityKycRepository identityKycRepository;
    private final DocumentAnalysisService documentAnalysisService;
    private final ProviderConfigRepository providerConfigRepository;
    private final Map<String, KycProviderAdapter> providersByCode;
    private final KycFlowProperties properties;
    private final ObjectMapper objectMapper;

    public KycFlowService(KycCaseRepository kycCaseRepository,
                          KycDocumentRepository kycDocumentRepository,
                          IdentityRepository identityRepository,
                          IdentityKycRepository identityKycRepository,
                          DocumentAnalysisService documentAnalysisService,
                          ProviderConfigRepository providerConfigRepository,
                          List<KycProviderAdapter> providers,
                          KycFlowProperties properties,
                          ObjectMapper objectMapper) {
        this.kycCaseRepository = kycCaseRepository;
        this.kycDocumentRepository = kycDocumentRepository;
        this.identityRepository = identityRepository;
        this.identityKycRepository = identityKycRepository;
        this.documentAnalysisService = documentAnalysisService;
        this.providerConfigRepository = providerConfigRepository;
        this.providersByCode = providers.stream().collect(Collectors.toMap(KycProviderAdapter::providerCode, Function.identity()));
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(java.util.UUID caseId) {
        Optional<KycCaseEntity> optionalCase = kycCaseRepository.findById(caseId);
        if (optionalCase.isEmpty()) {
            return;
        }

        KycCaseEntity kycCase = optionalCase.get();
        List<KycStepType> steps = properties.stepsFor(kycCase.getLevel());
        Map<String, Object> metadata = readMetadata(kycCase.getMetadataJson());
        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
        KycDocumentEntity primaryDocument = latestIdentityDocument(documents);

        KycCaseStatus status = KycCaseStatus.INITIATED;
        String reason = null;
        List<Integer> signals = new ArrayList<>();

        if (steps.contains(KycStepType.OTP)) {
            if (!asBoolean(metadata.get("contactVerified"))) {
                persist(kycCase, status, signals, reason, null, primaryDocument);
                return;
            }
            status = KycCaseStatus.CONTACT_VERIFIED;
            signals.add(100);
        }

        if (requiresIdentityChecks(steps)) {
            if (primaryDocument == null) {
                persist(kycCase, status, signals, reason, null, primaryDocument);
                return;
            }
            if (primaryDocument.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE) {
                persist(kycCase, KycCaseStatus.REVIEW, List.of(35), "Document expiry is unreadable", null, primaryDocument);
                return;
            }
            if (primaryDocument.getValidityStatus() == KycDocumentValidityStatus.EXPIRED) {
                persist(kycCase, KycCaseStatus.REJECTED, List.of(20), "Document is expired", null, primaryDocument);
                return;
            }
            status = KycCaseStatus.IDENTITY_PRECHECKED;
            signals.add(primaryDocument.getDocumentNumber() == null ? 75 : 90);
            syncIdentity(primaryDocument, kycCase.getIdentity(), metadata);
        }

        if (steps.contains(KycStepType.FACE_MATCH)) {
            Integer faceScore = resolveFaceScore(primaryDocument, kycCase.getIdentity(), metadata);
            if (faceScore == null) {
                persist(kycCase, status, signals, reason, null, primaryDocument);
                return;
            }
            signals.add(faceScore);
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
            kycCase.setDocumentExpiryDate(primaryDocument.getExpiryDate());
            if (status == KycCaseStatus.APPROVED) {
                primaryDocument.setStatus(KycDocumentStatus.APPROVED);
                primaryDocument.setReviewedAt(Instant.now());
                kycDocumentRepository.save(primaryDocument);
            } else if (status == KycCaseStatus.REJECTED) {
                primaryDocument.setStatus(KycDocumentStatus.REJECTED);
                primaryDocument.setReviewReason(reason);
                primaryDocument.setReviewedAt(Instant.now());
                kycDocumentRepository.save(primaryDocument);
            }
        }
        kycCaseRepository.save(kycCase);
        syncMapping(kycCase);
    }

    private void syncIdentity(KycDocumentEntity primaryDocument, IdentityEntity identity, Map<String, Object> metadata) {
        if (primaryDocument.getDocumentNumber() != null && (identity.getDocumentNumber() == null || identity.getDocumentNumber().isBlank())) {
            identity.setDocumentNumber(primaryDocument.getDocumentNumber());
        }
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
            Object fullName = metadata.get("fullName");
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

    private Integer resolveFaceScore(KycDocumentEntity primaryDocument, IdentityEntity identity, Map<String, Object> metadata) {
        if (primaryDocument == null) {
            return null;
        }
        Integer explicitScore = asInteger(metadata.get("faceMatchScore"));
        if (explicitScore != null) {
            return explicitScore;
        }
        String uploadedFaceHash = readFaceHash(primaryDocument.getMetadataJson());
        if (uploadedFaceHash != null && identity.getFaceHash() != null) {
            return uploadedFaceHash.equals(identity.getFaceHash()) ? 90 : 40;
        }
        return null;
    }

    private String readFaceHash(String metadataJson) {
        return documentAnalysisService.analyze(metadataJson).faceHash();
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

    private boolean requiresIdentityChecks(List<KycStepType> steps) {
        return steps.contains(KycStepType.ID_UPLOAD)
                || steps.contains(KycStepType.OCR_EXTRACTION)
                || steps.contains(KycStepType.DOCUMENT_EXPIRY_CHECK);
    }

    private KycDocumentEntity latestIdentityDocument(List<KycDocumentEntity> documents) {
        return documents.stream()
                .filter(document -> document.getDocumentType() != KycDocumentType.SELFIE && document.getDocumentType() != KycDocumentType.FIELD_AGENT_PHOTO)
                .reduce((first, second) -> second)
                .orElse(null);
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
}
