package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.*;
import com.cpass.platform.kyc.infra.IdentityKycRepository;
import com.cpass.platform.kyc.infra.IdentityRepository;
import com.cpass.platform.kyc.infra.KycCaseRepository;
import com.cpass.platform.kyc.infra.KycDocumentRepository;
import com.cpass.platform.provider.infra.ProviderConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KycFlowServiceTest {

    private KycCaseRepository kycCaseRepository;
    private KycDocumentRepository kycDocumentRepository;
    private IdentityRepository identityRepository;
    private IdentityKycRepository identityKycRepository;
    private DocumentAnalysisService documentAnalysisService;
    private SelfieAnalysisService selfieAnalysisService;
    private ProviderConfigRepository providerConfigRepository;
    private KycFlowProperties properties;
    private KycOperationalMetricsService metricsService;
    private ObjectMapper objectMapper;

    private KycFlowService kycFlowService;

    @BeforeEach
    void setUp() {
        kycCaseRepository = mock(KycCaseRepository.class);
        kycDocumentRepository = mock(KycDocumentRepository.class);
        identityRepository = mock(IdentityRepository.class);
        identityKycRepository = mock(IdentityKycRepository.class);
        documentAnalysisService = mock(DocumentAnalysisService.class);
        selfieAnalysisService = mock(SelfieAnalysisService.class);
        providerConfigRepository = mock(ProviderConfigRepository.class);
        properties = mock(KycFlowProperties.class);
        metricsService = mock(KycOperationalMetricsService.class);
        objectMapper = new ObjectMapper();

        when(properties.stepsFor(any())).thenReturn(List.of(
                KycStepType.ID_UPLOAD,
                KycStepType.OCR_EXTRACTION,
                KycStepType.DOCUMENT_EXPIRY_CHECK,
                KycStepType.FACE_MATCH
        ));
        when(properties.autoApproveScoreThreshold()).thenReturn(75);
        when(properties.fallbackScoreThreshold()).thenReturn(70);
        when(properties.validityPeriod()).thenReturn(java.time.Duration.ofDays(365));

        kycFlowService = new KycFlowService(
                kycCaseRepository,
                kycDocumentRepository,
                identityRepository,
                identityKycRepository,
                documentAnalysisService,
                selfieAnalysisService,
                providerConfigRepository,
                List.of(),
                properties,
                metricsService,
                objectMapper
        );
    }

    @Test
    void shouldApproveWhenDocumentsAreCompleteAndSignalsCoherent() throws Exception {
        UUID caseId = UUID.randomUUID();
        KycCaseEntity kycCase = new KycCaseEntity();
        kycCase.setId(caseId);
        kycCase.setLevel(KycLevel.LEVEL_1);
        kycCase.setStatus(KycCaseStatus.INITIATED);
        kycCase.setIdentity(new IdentityEntity());
        kycCase.setMetadataJson(objectMapper.writeValueAsString(Map.of("birthDate", "1990-01-01")));

        KycDocumentEntity identityDoc = new KycDocumentEntity();
        identityDoc.setDocumentType(KycDocumentType.NATIONAL_ID);
        identityDoc.setValidityStatus(KycDocumentValidityStatus.VALID);
        identityDoc.setDocumentNumber("12345");
        identityDoc.setExpiryDate(LocalDate.now().plusYears(1));
        identityDoc.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "confidenceScore", 90,
                "supportedDocumentType", true,
                "extractedFields", Map.of("fullName", "JOHN DOE", "birthDate", "1990-01-01")
        )));

        KycDocumentEntity selfieDoc = new KycDocumentEntity();
        selfieDoc.setDocumentType(KycDocumentType.SELFIE);
        selfieDoc.setValidityStatus(KycDocumentValidityStatus.VALID);
        selfieDoc.setMetadataJson("{}");

        when(kycCaseRepository.findById(caseId)).thenReturn(Optional.of(kycCase));
        when(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).thenReturn(List.of(identityDoc, selfieDoc));
        when(documentAnalysisService.parse(any())).thenAnswer(i -> objectMapper.readValue((String) i.getArgument(0), Map.class));
        
        SelfieAnalysisService.SelfieAnalysisResult selfieResult = new SelfieAnalysisService.SelfieAnalysisResult(
                true, true, 100, true, 90, SelfieAnalysisService.FACE_DECISION_MATCH, 85, KycDocumentValidityStatus.VALID, Map.of()
        );
        when(selfieAnalysisService.analyze(any())).thenReturn(selfieResult);

        kycFlowService.process(caseId);

        assertThat(kycCase.getStatus()).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(kycCase.getScore()).isEqualTo(88); // Average of 90 and 85
        verify(kycCaseRepository).save(kycCase);
    }

    @Test
    void shouldReturnResubmissionRequiredWhenDocumentUnreadable() throws Exception {
        UUID caseId = UUID.randomUUID();
        KycCaseEntity kycCase = new KycCaseEntity();
        kycCase.setId(caseId);
        kycCase.setLevel(KycLevel.LEVEL_1);
        kycCase.setStatus(KycCaseStatus.INITIATED);

        KycDocumentEntity identityDoc = new KycDocumentEntity();
        identityDoc.setDocumentType(KycDocumentType.NATIONAL_ID);
        identityDoc.setValidityStatus(KycDocumentValidityStatus.UNREADABLE); // Unreadable

        when(kycCaseRepository.findById(caseId)).thenReturn(Optional.of(kycCase));
        when(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).thenReturn(List.of(identityDoc));

        kycFlowService.process(caseId);

        assertThat(kycCase.getStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(kycCase.getStatusReason()).isEqualTo("Document is unreadable. Please resubmit a clearer image.");
        verify(kycCaseRepository).save(kycCase);
    }

    @Test
    void shouldReturnResubmissionRequiredWhenSelfieUnreadable() throws Exception {
        UUID caseId = UUID.randomUUID();
        KycCaseEntity kycCase = new KycCaseEntity();
        kycCase.setId(caseId);
        kycCase.setLevel(KycLevel.LEVEL_1);
        kycCase.setStatus(KycCaseStatus.INITIATED);

        KycDocumentEntity identityDoc = new KycDocumentEntity();
        identityDoc.setDocumentType(KycDocumentType.NATIONAL_ID);
        identityDoc.setValidityStatus(KycDocumentValidityStatus.VALID);

        KycDocumentEntity selfieDoc = new KycDocumentEntity();
        selfieDoc.setDocumentType(KycDocumentType.SELFIE);

        when(kycCaseRepository.findById(caseId)).thenReturn(Optional.of(kycCase));
        when(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).thenReturn(List.of(identityDoc, selfieDoc));
        
        SelfieAnalysisService.SelfieAnalysisResult selfieResult = new SelfieAnalysisService.SelfieAnalysisResult(
                false, false, 0, false, 0, null, 0, KycDocumentValidityStatus.UNREADABLE, Map.of()
        );
        when(selfieAnalysisService.analyze(any())).thenReturn(selfieResult);

        kycFlowService.process(caseId);

        assertThat(kycCase.getStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(kycCase.getStatusReason()).isEqualTo("Selfie is unreadable. Please retry capture.");
        verify(kycCaseRepository).save(kycCase);
    }
    
    @Test
    void shouldReturnResubmissionRequiredOnMismatch() throws Exception {
        UUID caseId = UUID.randomUUID();
        KycCaseEntity kycCase = new KycCaseEntity();
        kycCase.setId(caseId);
        kycCase.setLevel(KycLevel.LEVEL_1);
        kycCase.setStatus(KycCaseStatus.INITIATED);
        kycCase.setIdentity(new IdentityEntity());
        kycCase.setMetadataJson(objectMapper.writeValueAsString(Map.of("fullName", "ALICE SMITH", "birthDate", "1990-01-01")));

        KycDocumentEntity identityDoc = new KycDocumentEntity();
        identityDoc.setDocumentType(KycDocumentType.NATIONAL_ID);
        identityDoc.setValidityStatus(KycDocumentValidityStatus.VALID);
        identityDoc.setDocumentNumber("12345");
        identityDoc.setExpiryDate(LocalDate.now().plusYears(1));
        identityDoc.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                "confidenceScore", 90,
                "supportedDocumentType", true,
                "extractedFields", Map.of("fullName", "BOB JONES", "birthDate", "1990-01-01") // Mismatch!
        )));

        when(kycCaseRepository.findById(caseId)).thenReturn(Optional.of(kycCase));
        when(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).thenReturn(List.of(identityDoc));
        when(documentAnalysisService.parse(any())).thenAnswer(i -> objectMapper.readValue((String) i.getArgument(0), Map.class));

        kycFlowService.process(caseId);

        assertThat(kycCase.getStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(kycCase.getStatusReason()).isEqualTo("Declared identity information does not match extracted document information.");
        verify(kycCaseRepository).save(kycCase);
    }
}
