package com.cpass.platform.kyc.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.domain.ClientScope;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.security.JwtService;
import com.cpass.platform.common.security.UserJwtService;
import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.kyc.domain.KycNextAction;
import com.cpass.platform.kyc.domain.KycReasonCode;
import com.cpass.platform.kyc.dto.KycCaseResponse;
import com.cpass.platform.kyc.dto.KycUploadResponse;
import com.cpass.platform.kyc.infra.IdentityKycRepository;
import com.cpass.platform.kyc.infra.IdentityRepository;
import com.cpass.platform.kyc.infra.KycCaseRepository;
import com.cpass.platform.kyc.infra.KycDocumentRepository;
import com.cpass.platform.user.domain.UserEntity;
import com.cpass.platform.user.domain.UserRole;
import com.cpass.platform.user.infra.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KycServiceUploadIntegrationTest {

    @Autowired
    private KycService kycService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserJwtService userJwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private IdentityKycRepository identityKycRepository;

    @Autowired
    private KycCaseRepository kycCaseRepository;

    @Autowired
    private KycDocumentRepository kycDocumentRepository;

    @MockBean
    private S3Client s3Client;

    @BeforeEach
    void setUp() {
        kycDocumentRepository.deleteAll();
        identityKycRepository.deleteAll();
        kycCaseRepository.deleteAll();
        identityRepository.deleteAll();
        clientRepository.deleteAll();
        userRepository.deleteAll();

        reset(s3Client);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());
    }

    @Test
    void shouldAttachIdentityDocumentToCaseOnUpload() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\"}",
                "P1234567",
                null
        );

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                file("passport.jpg"),
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );

        KycDocumentEntity document = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseEntity kycCase = kycCaseRepository.findById(caseId).orElseThrow();
        List<KycDocumentEntity> linkedDocuments = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);

        assertThat(document.getKycCase()).isNotNull();
        assertThat(document.getKycCase().getId()).isEqualTo(caseId);
        assertThat(document.getObjectKey()).contains(kycCase.getReferenceId());
        assertThat(linkedDocuments).extracting(KycDocumentEntity::getId).contains(response.documentId());
        assertThat(kycCase.getMetadataJson()).contains("\"identityDocumentId\":\"" + response.documentId() + "\"");
    }

    @Test
    void shouldAttachSelfieDocumentToCaseOnUpload() {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId);

        KycUploadResponse response = uploadStrongSelfie(clientId, caseId, 91);

        KycDocumentEntity document = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseEntity kycCase = kycCaseRepository.findById(caseId).orElseThrow();
        List<KycDocumentEntity> linkedDocuments = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);

        assertThat(document.getDocumentType()).isEqualTo(KycDocumentType.SELFIE);
        assertThat(document.getKycCase()).isNotNull();
        assertThat(document.getKycCase().getId()).isEqualTo(caseId);
        assertThat(document.getObjectKey()).contains(kycCase.getReferenceId());
        assertThat(linkedDocuments).extracting(KycDocumentEntity::getId).contains(response.documentId());
        assertThat(kycCase.getMetadataJson()).contains("\"selfieDocumentId\":\"" + response.documentId() + "\"");
    }

    @Test
    void shouldRequestResubmissionWhenIdentityDocumentIsUnreadable() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                null,
                "image/jpeg",
                "abc".getBytes(StandardCharsets.UTF_8)
        );

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                file,
                "{\"accountHolderId\":\"acc_1\",\"mediaFileId\":\"media_1\"}"
        );

        KycDocumentEntity saved = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseEntity savedCase = kycCaseRepository.findById(caseId).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(response.status()).isEqualTo(KycDocumentStatus.RESUBMISSION_REQUIRED);
        assertThat(response.validityStatus()).isEqualTo(KycDocumentValidityStatus.UNREADABLE);
        assertThat(response.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(response.reasonCode()).isEqualTo(KycReasonCode.DOCUMENT_UNREADABLE);
        assertThat(response.nextAction()).isEqualTo(KycNextAction.RESUBMIT_DOCUMENT);
        assertThat(saved.getDocumentType()).isEqualTo(KycDocumentType.PASSPORT);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.RESUBMISSION_REQUIRED);
        assertThat(saved.getFileName()).startsWith("passport-").endsWith(".jpg");
        assertThat(saved.getObjectKey()).endsWith(saved.getFileName());
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getSizeBytes()).isEqualTo(3);
        assertThat(savedCase.getStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.DOCUMENT_UNREADABLE);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RESUBMIT_DOCUMENT);
        assertStatusEndpoint(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.DOCUMENT_UNREADABLE,
                KycNextAction.RESUBMIT_DOCUMENT
        );

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void shouldApproveReadablePassportWhenDeclaredIdentityMatches() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\",\"faceHash\":\"face-a\"}",
                "P1234567",
                "face-a"
        );

        KycUploadResponse documentResponse = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                file("passport.png"),
                "{\"documentType\":\"PASSPORT\",\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );
        KycUploadResponse selfieResponse = uploadStrongSelfie(clientId, caseId, 92);

        KycDocumentEntity saved = kycDocumentRepository.findById(documentResponse.documentId()).orElseThrow();
        KycDocumentEntity selfie = kycDocumentRepository.findById(selfieResponse.documentId()).orElseThrow();
        KycCaseEntity savedCase = kycCaseRepository.findById(caseId).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(saved.getDocumentType()).isEqualTo(KycDocumentType.PASSPORT);
        assertThat(saved.getValidityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.APPROVED);
        assertThat(selfie.getDocumentType()).isEqualTo(KycDocumentType.SELFIE);
        assertThat(selfie.getValidityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(savedCase.getStatus()).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(status.nextAction()).isNull();
        assertThat(status.reasonCode()).isNull();
        assertThat(status.extractedFields()).containsEntry("detectedDocumentType", "PASSPORT");
        assertThat(status.extractedFields()).containsEntry("documentNumber", "P1234567");
        assertBiometricSignals(status, "MATCH", 92);
        assertThat(status.confidenceScore()).isGreaterThanOrEqualTo(90);
        assertStatusEndpointWithBiometrics(clientId, caseId, KycCaseStatus.APPROVED, null, null, "MATCH");
    }

    @Test
    void shouldRecoverCompleteInitiatedCaseAndReturnApprovedStatus() {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\",\"faceHash\":\"face-a\"}",
                "P1234567",
                "face-a"
        );

        persistDocument(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                "{\"documentType\":\"PASSPORT\",\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );
        persistDocument(
                clientId,
                caseId,
                KycDocumentType.SELFIE,
                "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":90,\"faceDetectedOnDocument\":true,\"faceMatchScore\":92}"
        );

        assertThat(kycCaseRepository.findById(caseId).orElseThrow().getStatus()).isEqualTo(KycCaseStatus.INITIATED);

        KycCaseResponse status = kycService.getCase(clientId, caseId);
        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(
                kycCaseRepository.findById(caseId).orElseThrow()
        );

        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(status.productStatus()).isEqualTo("VERIFIED");
        assertThat(status.reasonCode()).isNull();
        assertThat(status.nextAction()).isNull();
        assertThat(documents).allMatch(document -> document.getValidityStatus() == KycDocumentValidityStatus.VALID);
        assertBiometricSignals(status, "MATCH", 92);
    }

    @Test
    void shouldRecoverReferenceKeyedOrphanDocumentsAndReturnApprovedStatus() {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\",\"faceHash\":\"face-a\"}",
                "P1234567",
                "face-a"
        );
        KycCaseEntity kycCase = kycCaseRepository.findById(caseId).orElseThrow();

        KycDocumentEntity identityDocument = persistOrphanDocumentWithReferenceObjectKey(
                clientId,
                kycCase,
                KycDocumentType.PASSPORT,
                "{\"documentType\":\"PASSPORT\",\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );
        KycDocumentEntity selfieDocument = persistOrphanDocumentWithReferenceObjectKey(
                clientId,
                kycCase,
                KycDocumentType.SELFIE,
                "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":90,\"faceDetectedOnDocument\":true,\"faceMatchScore\":92}"
        );

        assertThat(kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase)).isEmpty();

        KycCaseResponse status = kycService.getCase(clientId, caseId);
        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(
                kycCaseRepository.findById(caseId).orElseThrow()
        );

        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(status.productStatus()).isEqualTo("VERIFIED");
        assertThat(documents).extracting(KycDocumentEntity::getId)
                .containsExactly(identityDocument.getId(), selfieDocument.getId());
        assertThat(documents).allMatch(document -> document.getKycCase() != null && document.getKycCase().getId().equals(caseId));
        assertThat(documents).allMatch(document -> document.getValidityStatus() == KycDocumentValidityStatus.VALID);
        assertBiometricSignals(status, "MATCH", 92);
    }

    @Test
    void shouldRecoverUnreadableDocumentIntoResubmissionRequired() {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId, "{\"contactVerified\":true}", null, null);
        persistDocument(clientId, caseId, KycDocumentType.PASSPORT, "{}");

        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.DOCUMENT_UNREADABLE);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RESUBMIT_DOCUMENT);
    }

    @Test
    void shouldRecoverUnreadableSelfieIntoResubmissionRequired() {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\"}",
                "P1234567",
                null
        );
        persistDocument(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );
        persistDocument(
                clientId,
                caseId,
                KycDocumentType.SELFIE,
                "{\"faceDetectedOnSelfie\":false,\"selfieQuality\":85}"
        );

        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.SELFIE_UNREADABLE);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RETRY_SELFIE_CAPTURE);
    }

    @Test
    void shouldRecoverDeclaredMismatchIntoResubmissionRequired() {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\"}",
                "P1234567",
                null
        );
        persistDocument(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P9999999\"}"
        );

        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.DECLARED_INFO_MISMATCH);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.UPDATE_DECLARED_INFO);
    }

    @Test
    void shouldApproveReadableNationalIdWhenDeclaredIdentityMatches() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"NID-778899\",\"faceHash\":\"face-a\"}",
                "NID-778899",
                "face-a"
        );

        KycUploadResponse documentResponse = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.NATIONAL_ID,
                file("national-id.png"),
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2029-03-15\",\"documentNumber\":\"NID-778899\"}"
        );
        uploadStrongSelfie(clientId, caseId, 90);

        KycDocumentEntity saved = kycDocumentRepository.findById(documentResponse.documentId()).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(saved.getDocumentType()).isEqualTo(KycDocumentType.NATIONAL_ID);
        assertThat(saved.getValidityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.APPROVED);
        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(status.extractedFields()).containsEntry("detectedDocumentType", "NATIONAL_ID");
        assertThat(status.extractedFields()).containsEntry("birthDate", "1990-05-20");
        assertBiometricSignals(status, "MATCH", 90);
        assertThat(status.confidenceScore()).isGreaterThanOrEqualTo(90);
        assertStatusEndpointWithBiometrics(clientId, caseId, KycCaseStatus.APPROVED, null, null, "MATCH");
    }

    @Test
    void shouldRouteCoherentDocumentToReviewWhenAutomaticSignalsAreInsufficient() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\"}",
                "P1234567",
                null
        );

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                file("passport.png"),
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );

        KycDocumentEntity saved = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(saved.getValidityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.PENDING);
        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.REVIEW);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.MANUAL_REVIEW_REQUIRED);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.WAIT_FOR_REVIEW);
        assertThat(status.extractedFields()).containsEntry("detectedDocumentType", "PASSPORT");
        assertThat(status.confidenceScore()).isGreaterThanOrEqualTo(90);
        assertStatusEndpoint(
                clientId,
                caseId,
                KycCaseStatus.REVIEW,
                KycReasonCode.MANUAL_REVIEW_REQUIRED,
                KycNextAction.WAIT_FOR_REVIEW
        );
    }

    @Test
    void shouldRequestSelfieRetryWhenSelfieIsUnreadable() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId, "{\"contactVerified\":true}", null, null);

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.SELFIE,
                file("selfie.jpg"),
                "{\"ocrReadable\":false}"
        );

        KycDocumentEntity saved = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseEntity savedCase = kycCaseRepository.findById(caseId).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(response.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(response.reasonCode()).isEqualTo(KycReasonCode.SELFIE_UNREADABLE);
        assertThat(response.nextAction()).isEqualTo(KycNextAction.RETRY_SELFIE_CAPTURE);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.RESUBMISSION_REQUIRED);
        assertThat(savedCase.getStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.SELFIE_UNREADABLE);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RETRY_SELFIE_CAPTURE);
        assertStatusEndpoint(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.SELFIE_UNREADABLE,
                KycNextAction.RETRY_SELFIE_CAPTURE
        );
    }

    @Test
    void shouldRequestSelfieRetryWhenNoFaceIsDetected() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId, "{\"contactVerified\":true}", null, null);

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.SELFIE,
                file("selfie.jpg"),
                "{\"faceDetectedOnSelfie\":false,\"selfieQuality\":85}"
        );

        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(response.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(response.reasonCode()).isEqualTo(KycReasonCode.SELFIE_UNREADABLE);
        assertThat(response.nextAction()).isEqualTo(KycNextAction.RETRY_SELFIE_CAPTURE);
        assertThat(response.extractedFields()).containsEntry("faceDecision", "SELFIE_UNUSABLE");
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.SELFIE_UNREADABLE);
        assertBiometricSignals(status, "SELFIE_UNUSABLE", null);
        assertStatusEndpointWithBiometrics(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.SELFIE_UNREADABLE,
                KycNextAction.RETRY_SELFIE_CAPTURE,
                "SELFIE_UNUSABLE"
        );
    }

    @Test
    void shouldRequestSelfieRetryWhenSelfieQualityIsTooLow() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId, "{\"contactVerified\":true}", null, null);

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.SELFIE,
                file("selfie.jpg"),
                "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":45,\"faceMatchScore\":90}"
        );

        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(response.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(response.reasonCode()).isEqualTo(KycReasonCode.SELFIE_UNREADABLE);
        assertThat(response.nextAction()).isEqualTo(KycNextAction.RETRY_SELFIE_CAPTURE);
        assertThat(response.extractedFields()).containsEntry("faceDecision", "SELFIE_UNUSABLE");
        assertThat(response.extractedFields()).containsEntry("selfieQuality", 45);
        assertBiometricSignals(status, "SELFIE_UNUSABLE", 90);
        assertStatusEndpointWithBiometrics(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.SELFIE_UNREADABLE,
                KycNextAction.RETRY_SELFIE_CAPTURE,
                "SELFIE_UNUSABLE"
        );
    }

    @Test
    void shouldRouteCoherentDocumentToReviewWhenFaceMatchIsInconclusive() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\"}",
                "P1234567",
                null
        );

        kycService.upload(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                file("passport.png"),
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );
        uploadSelfie(clientId, caseId, "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":86,\"faceDetectedOnDocument\":true,\"faceMatchScore\":65}");

        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.REVIEW);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.MANUAL_REVIEW_REQUIRED);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.WAIT_FOR_REVIEW);
        assertThat(status.statusReason()).contains("Biometric face match is inconclusive");
        assertBiometricSignals(status, "INCONCLUSIVE", 65);
        assertStatusEndpointWithBiometrics(
                clientId,
                caseId,
                KycCaseStatus.REVIEW,
                KycReasonCode.MANUAL_REVIEW_REQUIRED,
                KycNextAction.WAIT_FOR_REVIEW,
                "INCONCLUSIVE"
        );
    }

    @Test
    void shouldRequestResubmissionWhenFaceMatchIsStrongMismatch() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\"}",
                "P1234567",
                null
        );

        kycService.upload(
                clientId,
                caseId,
                KycDocumentType.PASSPORT,
                file("passport.png"),
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );
        uploadSelfie(clientId, caseId, "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":88,\"faceDetectedOnDocument\":true,\"faceMatchScore\":35}");

        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(status.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.BIOMETRIC_MISMATCH);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RETRY_SELFIE_CAPTURE);
        assertThat(status.statusReason()).contains("Biometric face match does not match");
        assertBiometricSignals(status, "MISMATCH", 35);
        assertStatusEndpointWithBiometrics(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.BIOMETRIC_MISMATCH,
                KycNextAction.RETRY_SELFIE_CAPTURE,
                "MISMATCH"
        );
    }

    @Test
    void shouldAskForDeclaredInfoUpdateWhenDocumentNumberDoesNotMatch() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"BJ-123456\",\"faceHash\":\"face-a\"}",
                "BJ-123456",
                "face-a"
        );

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.NATIONAL_ID,
                file("id-card.png"),
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"BJ-999999\",\"faceMatchScore\":90}"
        );

        KycDocumentEntity saved = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseEntity savedCase = kycCaseRepository.findById(caseId).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(saved.getDocumentType()).isEqualTo(KycDocumentType.NATIONAL_ID);
        assertThat(saved.getValidityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.RESUBMISSION_REQUIRED);
        assertThat(savedCase.getStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.DECLARED_INFO_MISMATCH);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.UPDATE_DECLARED_INFO);
        assertStatusEndpoint(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.DECLARED_INFO_MISMATCH,
                KycNextAction.UPDATE_DECLARED_INFO
        );
    }

    @Test
    void shouldRejectExpiredIdentityDocument() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"DL-123456\",\"faceHash\":\"face-a\"}",
                "DL-123456",
                "face-a"
        );

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.DRIVER_LICENSE,
                file("driver-license.png"),
                "{\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2020-01-10\",\"documentNumber\":\"DL-123456\",\"faceMatchScore\":90}"
        );

        KycDocumentEntity saved = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseEntity savedCase = kycCaseRepository.findById(caseId).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(saved.getDocumentType()).isEqualTo(KycDocumentType.DRIVER_LICENSE);
        assertThat(saved.getValidityStatus()).isEqualTo(KycDocumentValidityStatus.EXPIRED);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.REJECTED);
        assertThat(savedCase.getStatus()).isEqualTo(KycCaseStatus.REJECTED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.DOCUMENT_EXPIRED);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RESUBMIT_DOCUMENT);
        assertThat(status.extractedFields()).containsEntry("detectedDocumentType", "DRIVER_LICENSE");
        assertThat(status.confidenceScore()).isEqualTo(60);
        assertStatusEndpoint(
                clientId,
                caseId,
                KycCaseStatus.REJECTED,
                KycReasonCode.DOCUMENT_EXPIRED,
                KycNextAction.RESUBMIT_DOCUMENT
        );
    }

    @Test
    void shouldRequestResubmissionForUnsupportedIdentityDocumentType() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId, "{\"contactVerified\":true}", null, null);

        KycUploadResponse response = kycService.upload(
                clientId,
                caseId,
                KycDocumentType.BUSINESS_REG,
                file("business-reg.png"),
                "{\"fullName\":\"Grace Doe\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"BR-123\"}"
        );

        KycDocumentEntity saved = kycDocumentRepository.findById(response.documentId()).orElseThrow();
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(response.caseStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(response.reasonCode()).isEqualTo(KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE);
        assertThat(response.nextAction()).isEqualTo(KycNextAction.RESUBMIT_DOCUMENT);
        assertThat(saved.getStatus()).isEqualTo(KycDocumentStatus.RESUBMISSION_REQUIRED);
        assertThat(saved.getValidityStatus()).isEqualTo(KycDocumentValidityStatus.UNREADABLE);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RESUBMIT_DOCUMENT);
        assertThat(status.extractedFields()).containsEntry("supportedDocumentType", false);
        assertThat(status.confidenceScore()).isEqualTo(20);
        assertStatusEndpoint(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE,
                KycNextAction.RESUBMIT_DOCUMENT
        );
    }

    @Test
    void shouldAggregateThreeUnreadableDocumentsIntoResubmissionRequired() throws Exception {
        UUID clientId = createClient();
        UUID caseId = createCase(clientId, "{\"contactVerified\":true}", null, null);

        kycService.upload(clientId, caseId, KycDocumentType.NATIONAL_ID, file("id-card.jpg"), "{}");
        kycService.upload(clientId, caseId, KycDocumentType.PASSPORT, file("passport.jpg"), "{}");
        kycService.upload(clientId, caseId, KycDocumentType.VOTER_CARD, file("voter-card.jpg"), "{}");

        KycCaseEntity savedCase = kycCaseRepository.findById(caseId).orElseThrow();
        List<KycDocumentEntity> documents = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(savedCase);
        KycCaseResponse status = kycService.getCase(clientId, caseId);

        assertThat(documents).hasSize(3);
        assertThat(documents).allMatch(document -> document.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE);
        assertThat(documents).allMatch(document -> document.getStatus() == KycDocumentStatus.RESUBMISSION_REQUIRED);
        assertThat(savedCase.getStatus()).isEqualTo(KycCaseStatus.RESUBMISSION_REQUIRED);
        assertThat(status.reasonCode()).isEqualTo(KycReasonCode.DOCUMENT_UNREADABLE);
        assertThat(status.nextAction()).isEqualTo(KycNextAction.RESUBMIT_DOCUMENT);
        assertStatusEndpoint(
                clientId,
                caseId,
                KycCaseStatus.RESUBMISSION_REQUIRED,
                KycReasonCode.DOCUMENT_UNREADABLE,
                KycNextAction.RESUBMIT_DOCUMENT
        );
    }

    @Test
    void shouldVerifyPublicKycFlowAndExposeCommercialContract() throws Exception {
        UUID clientId = createClient();
        String token = jwtService.issueToken(clientRepository.findById(clientId).orElseThrow()).token();
        String requestId = "req-verify-ok";

        mockMvc.perform(multipart("/kyc/verify")
                        .file(jsonPart(
                                "identity",
                                """
                                        {
                                          "userId": "user_123",
                                          "fullName": "Grace Doe",
                                          "birthDate": "1990-05-20",
                                          "phoneNumber": "+22962000000",
                                          "documentNumber": "P1234567",
                                          "contactVerified": true
                                        }
                                        """
                        ))
                        .file(filePart("document", "passport.jpg"))
                        .file(filePart("selfie", "selfie.jpg"))
                        .file(jsonPart("documentMetadata", "{\"documentType\":\"PASSPORT\",\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"))
                        .file(jsonPart("selfieMetadata", "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":90,\"faceDetectedOnDocument\":true,\"faceMatchScore\":92}"))
                        .param("documentType", KycDocumentType.PASSPORT.name())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", requestId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.caseId").hasJsonPath())
                .andExpect(jsonPath("$.caseStatus").value(KycCaseStatus.APPROVED.name()))
                .andExpect(jsonPath("$.productStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.reasonCode").value(nullValue()))
                .andExpect(jsonPath("$.nextAction").value(nullValue()))
                .andExpect(jsonPath("$.extractedFields.documentNumber").value("P1234567"))
                .andExpect(jsonPath("$.extractedFields.biometricSignals.faceDecision").value("MATCH"));
    }

    @Test
    void shouldReturnPublicVerifyResubmissionWhenDocumentIsUnreadable() throws Exception {
        UUID clientId = createClient();
        String token = jwtService.issueToken(clientRepository.findById(clientId).orElseThrow()).token();

        mockMvc.perform(multipart("/kyc/verify")
                        .file(jsonPart("identity", "{\"userId\":\"user_123\",\"fullName\":\"Grace Doe\",\"contactVerified\":true}"))
                        .file(filePart("document", "passport.jpg"))
                        .file(filePart("selfie", "selfie.jpg"))
                        .file(jsonPart("documentMetadata", "{}"))
                        .file(jsonPart("selfieMetadata", "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":90,\"faceDetectedOnDocument\":true,\"faceMatchScore\":92}"))
                        .param("documentType", KycDocumentType.PASSPORT.name())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "req-verify-unreadable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseStatus").value(KycCaseStatus.RESUBMISSION_REQUIRED.name()))
                .andExpect(jsonPath("$.productStatus").value(KycCaseStatus.RESUBMISSION_REQUIRED.name()))
                .andExpect(jsonPath("$.reasonCode").value(KycReasonCode.DOCUMENT_UNREADABLE.name()))
                .andExpect(jsonPath("$.nextAction").value(KycNextAction.RESUBMIT_DOCUMENT.name()))
                .andExpect(jsonPath("$.statusReason").value(KycFlowService.REASON_DOCUMENT_UNREADABLE));
    }

    @Test
    void shouldRejectKycStatusWhenBearerTokenIsInvalid() throws Exception {
        String requestId = "req-invalid-token";

        mockMvc.perform(get("/kyc/status")
                        .param("caseId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer invalid-token")
                        .header("X-Request-Id", requestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void shouldRejectKycStatusWhenApiCredentialsAreMissing() throws Exception {
        String requestId = "req-missing-auth";

        mockMvc.perform(get("/kyc/status")
                        .param("caseId", UUID.randomUUID().toString())
                        .header("X-Request-Id", requestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.code").value("AUTH_HEADERS_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void shouldRejectPublicVerifyWhenIdentityInputIsInvalid() throws Exception {
        UUID clientId = createClient();
        String token = jwtService.issueToken(clientRepository.findById(clientId).orElseThrow()).token();

        mockMvc.perform(multipart("/kyc/verify")
                        .file(jsonPart("identity", "{\"userId\":\"\",\"contactVerified\":true}"))
                        .file(filePart("document", "passport.jpg"))
                        .file(filePart("selfie", "selfie.jpg"))
                        .param("documentType", KycDocumentType.PASSPORT.name())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "req-invalid-input"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value("req-invalid-input"));
    }

    @Test
    void shouldRejectPublicVerifyWhenDocumentTypeIsNotCommerciallySupported() throws Exception {
        UUID clientId = createClient();
        String token = jwtService.issueToken(clientRepository.findById(clientId).orElseThrow()).token();

        mockMvc.perform(multipart("/kyc/verify")
                        .file(jsonPart("identity", "{\"userId\":\"user_123\",\"contactVerified\":true}"))
                        .file(filePart("document", "business-reg.jpg"))
                        .file(filePart("selfie", "selfie.jpg"))
                        .param("documentType", KycDocumentType.BUSINESS_REG.name())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "req-unsupported-doc-type"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"))
                .andExpect(jsonPath("$.requestId").value("req-unsupported-doc-type"));
    }

    @Test
    void shouldExposeKycOperationalStatsForPilotMonitoring() throws Exception {
        UUID clientId = createClient();
        UUID unreadableCaseId = createCase(clientId);
        kycService.upload(
                clientId,
                unreadableCaseId,
                KycDocumentType.PASSPORT,
                file("unreadable-passport.jpg"),
                "{\"accountHolderId\":\"acc_1\"}"
        );

        UUID approvedCaseId = createCase(
                clientId,
                "{\"contactVerified\":true,\"fullName\":\"Grace Doe\",\"birthDate\":\"1990-05-20\",\"documentNumber\":\"P1234567\",\"faceHash\":\"face-a\"}",
                "P1234567",
                "face-a"
        );
        kycService.upload(
                clientId,
                approvedCaseId,
                KycDocumentType.PASSPORT,
                file("passport.png"),
                "{\"documentType\":\"PASSPORT\",\"fullName\":\"DOE GRACE\",\"birthDate\":\"1990-05-20\",\"expiryDate\":\"2028-01-10\",\"documentNumber\":\"P1234567\"}"
        );
        uploadStrongSelfie(clientId, approvedCaseId, 92);

        mockMvc.perform(get("/admin/kyc/stats")
                        .header("Authorization", "Bearer " + createAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCases").value(2))
                .andExpect(jsonPath("$.verified.count").value(1))
                .andExpect(jsonPath("$.verified.rate").value(50.0))
                .andExpect(jsonPath("$.resubmission.count").value(1))
                .andExpect(jsonPath("$.resubmission.rate").value(50.0))
                .andExpect(jsonPath("$.topReasonCodes[0].key").value(KycReasonCode.DOCUMENT_UNREADABLE.name()))
                .andExpect(jsonPath("$.friction.topUnreadableCauses[0].key").value(KycReasonCode.DOCUMENT_UNREADABLE.name()))
                .andExpect(jsonPath("$.friction.leastPerformingDocumentTypes[0].documentType").value(KycDocumentType.PASSPORT.name()))
                .andExpect(jsonPath("$.authFailures.total").hasJsonPath())
                .andExpect(jsonPath("$.dataDrivenV2Priorities").isArray());
    }

    private UUID createClient() {
        ClientEntity client = new ClientEntity();
        client.setName("kyc-test-" + UUID.randomUUID());
        client.setEmail("kyc-" + UUID.randomUUID() + "@test.local");
        client.setClientId("cl_" + UUID.randomUUID().toString().replace("-", ""));
        client.setSmsSoftQuota(1000);
        client.setSmsHardQuota(1000);
        client.setWhatsappSoftQuota(1000);
        client.setWhatsappHardQuota(1000);
        client.setRateLimitPerMinute(100);
        client.setEnabled(true);
        client.setKycLevelRequired(KycLevel.LEVEL_1);
        client.setScopes(Set.of(ClientScope.KYC_READ, ClientScope.KYC_WRITE));
        return clientRepository.saveAndFlush(client).getId();
    }

    private String createAdminToken() {
        UserEntity admin = new UserEntity();
        admin.setEmail("admin-" + UUID.randomUUID() + "@test.local");
        admin.setTwoFactorEmail(admin.getEmail());
        admin.setPasswordHash("hash");
        admin.setRole(UserRole.ADMIN);
        admin.setEnabled(true);
        UserEntity saved = userRepository.saveAndFlush(admin);
        return userJwtService.issueToken(saved).token();
    }

    private UUID createCase(UUID clientId) {
        return createCase(clientId, "{}", null, null);
    }

    private UUID createCase(UUID clientId, String metadataJson, String documentNumber, String faceHash) {
        IdentityEntity identity = new IdentityEntity();
        identity.setFullName("Grace Doe");
        identity.setPhoneNumber("+22962000000");
        identity.setDocumentNumber(documentNumber);
        identity.setFaceHash(faceHash);
        identityRepository.saveAndFlush(identity);

        KycCaseEntity kycCase = new KycCaseEntity();
        kycCase.setClientId(clientId);
        kycCase.setIdentity(identity);
        kycCase.setUserId("user_123");
        kycCase.setLevel(KycLevel.LEVEL_1);
        kycCase.setStatus(KycCaseStatus.INITIATED);
        kycCase.setReferenceId("kyc_" + UUID.randomUUID().toString().replace("-", ""));
        kycCase.setMetadataJson(metadataJson);
        return kycCaseRepository.saveAndFlush(kycCase).getId();
    }

    private MockMultipartFile file(String fileName) {
        return new MockMultipartFile(
                "file",
                fileName,
                "image/jpeg",
                "abc".getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile filePart(String partName, String fileName) {
        return new MockMultipartFile(
                partName,
                fileName,
                "image/jpeg",
                "abc".getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile jsonPart(String partName, String json) {
        return new MockMultipartFile(
                partName,
                "",
                MediaType.APPLICATION_JSON_VALUE,
                json.getBytes(StandardCharsets.UTF_8)
        );
    }

    private KycDocumentEntity persistDocument(UUID clientId, UUID caseId, KycDocumentType documentType, String metadataJson) {
        KycCaseEntity kycCase = kycCaseRepository.findById(caseId).orElseThrow();
        KycDocumentEntity document = new KycDocumentEntity();
        document.setClientId(clientId);
        document.setKycCase(kycCase);
        document.setDocumentType(documentType);
        document.setFileName(documentType.name().toLowerCase() + ".jpg");
        document.setObjectKey(clientId + "/" + caseId + "/" + document.getFileName());
        document.setBucketName("cpass-kyc");
        document.setContentType("image/jpeg");
        document.setSizeBytes(3);
        document.setStatus(KycDocumentStatus.PENDING);
        document.setMetadataJson(metadataJson);
        return kycDocumentRepository.saveAndFlush(document);
    }

    private KycDocumentEntity persistOrphanDocumentWithReferenceObjectKey(UUID clientId,
                                                                          KycCaseEntity kycCase,
                                                                          KycDocumentType documentType,
                                                                          String metadataJson) {
        KycDocumentEntity document = new KycDocumentEntity();
        document.setClientId(clientId);
        document.setDocumentType(documentType);
        document.setFileName(documentType.name().toLowerCase() + ".jpg");
        document.setObjectKey(clientId + "/" + kycCase.getReferenceId() + "/" + document.getFileName());
        document.setBucketName("cpass-kyc");
        document.setContentType("image/jpeg");
        document.setSizeBytes(3);
        document.setStatus(KycDocumentStatus.PENDING);
        document.setMetadataJson(metadataJson);
        return kycDocumentRepository.saveAndFlush(document);
    }

    private KycUploadResponse uploadStrongSelfie(UUID clientId, UUID caseId, int faceMatchScore) {
        return uploadSelfie(
                clientId,
                caseId,
                "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":90,\"faceDetectedOnDocument\":true,\"faceMatchScore\":" + faceMatchScore + "}"
        );
    }

    private KycUploadResponse uploadSelfie(UUID clientId, UUID caseId, String metadataJson) {
        return kycService.upload(
                clientId,
                caseId,
                KycDocumentType.SELFIE,
                file("selfie.jpg"),
                metadataJson
        );
    }

    @SuppressWarnings("unchecked")
    private void assertBiometricSignals(KycCaseResponse status, String expectedFaceDecision, Integer expectedFaceMatchScore) {
        assertThat(status.extractedFields()).containsKey("biometricSignals");
        Map<String, Object> biometricSignals = (Map<String, Object>) status.extractedFields().get("biometricSignals");
        assertThat(biometricSignals).containsEntry("faceDecision", expectedFaceDecision);
        if (expectedFaceMatchScore == null) {
            assertThat(biometricSignals).doesNotContainKey("faceMatchScore");
        } else {
            assertThat(biometricSignals).containsEntry("faceMatchScore", expectedFaceMatchScore);
        }
    }

    private void assertStatusEndpointWithBiometrics(UUID clientId,
                                                    UUID caseId,
                                                    KycCaseStatus expectedStatus,
                                                    KycReasonCode expectedReasonCode,
                                                    KycNextAction expectedNextAction,
                                                    String expectedFaceDecision) throws Exception {
        assertStatusEndpoint(clientId, caseId, expectedStatus, expectedReasonCode, expectedNextAction);
        String token = jwtService.issueToken(clientRepository.findById(clientId).orElseThrow()).token();
        mockMvc.perform(get("/kyc/status")
                        .param("caseId", caseId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extractedFields.biometricSignals.faceDecision").value(expectedFaceDecision))
                .andExpect(jsonPath("$.extractedFields.biometricSignals.selfieReadability").hasJsonPath())
                .andExpect(jsonPath("$.extractedFields.biometricSignals.selfieQuality").hasJsonPath())
                .andExpect(jsonPath("$.extractedFields.biometricSignals.biometricConfidenceScore").hasJsonPath());
    }

    private void assertStatusEndpoint(UUID clientId,
                                      UUID caseId,
                                      KycCaseStatus expectedStatus,
                                      KycReasonCode expectedReasonCode,
                                      KycNextAction expectedNextAction) throws Exception {
        String token = jwtService.issueToken(clientRepository.findById(clientId).orElseThrow()).token();
        String requestId = "req-status-" + UUID.randomUUID();
        var result = mockMvc.perform(get("/kyc/status")
                        .param("caseId", caseId.toString())
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", requestId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.caseStatus").value(expectedStatus.name()))
                .andExpect(jsonPath("$.status").value(expectedStatus.name()))
                .andExpect(jsonPath("$.productStatus").value(productStatus(expectedStatus)))
                .andExpect(jsonPath("$.statusReason").hasJsonPath())
                .andExpect(jsonPath("$.reasonCode").hasJsonPath())
                .andExpect(jsonPath("$.nextAction").hasJsonPath())
                .andExpect(jsonPath("$.recommendation").hasJsonPath())
                .andExpect(jsonPath("$.extractedFields").hasJsonPath())
                .andExpect(jsonPath("$.confidenceScore").hasJsonPath());

        if (expectedReasonCode == null) {
            result.andExpect(jsonPath("$.reasonCode").value(nullValue()))
                    .andExpect(jsonPath("$.nextAction").value(nullValue()))
                    .andExpect(jsonPath("$.recommendation").value(nullValue()));
        } else {
            result.andExpect(jsonPath("$.reasonCode").value(expectedReasonCode.name()));
            if (expectedNextAction == null) {
                result.andExpect(jsonPath("$.nextAction").value(nullValue()));
            } else {
                result.andExpect(jsonPath("$.nextAction").value(expectedNextAction.name()));
            }
        }
    }

    private String productStatus(KycCaseStatus status) {
        return status == KycCaseStatus.APPROVED ? "VERIFIED" : status.name();
    }
}
