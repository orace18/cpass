package com.cpass.platform.kyc.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.kyc.dto.KycUploadResponse;
import com.cpass.platform.kyc.infra.IdentityKycRepository;
import com.cpass.platform.kyc.infra.IdentityRepository;
import com.cpass.platform.kyc.infra.KycCaseRepository;
import com.cpass.platform.kyc.infra.KycDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class KycServiceUploadIntegrationTest {

    @Autowired
    private KycService kycService;

    @Autowired
    private ClientRepository clientRepository;

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

        reset(s3Client);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());
    }

    @Test
    void shouldUploadPassportWhenMultipartFilenameIsMissing() {
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

        assertThat(response.status()).isEqualTo(KycDocumentStatus.PENDING);
        assertThat(response.validityStatus()).isEqualTo(KycDocumentValidityStatus.UNREADABLE);
        assertThat(saved.getDocumentType()).isEqualTo(KycDocumentType.PASSPORT);
        assertThat(saved.getFileName()).startsWith("passport-").endsWith(".jpg");
        assertThat(saved.getObjectKey()).endsWith(saved.getFileName());
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getSizeBytes()).isEqualTo(3);

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
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
        return clientRepository.saveAndFlush(client).getId();
    }

    private UUID createCase(UUID clientId) {
        IdentityEntity identity = new IdentityEntity();
        identity.setFullName("Grace Doe");
        identity.setPhoneNumber("+22962000000");
        identityRepository.saveAndFlush(identity);

        KycCaseEntity kycCase = new KycCaseEntity();
        kycCase.setClientId(clientId);
        kycCase.setIdentity(identity);
        kycCase.setUserId("user_123");
        kycCase.setLevel(KycLevel.LEVEL_1);
        kycCase.setStatus(KycCaseStatus.INITIATED);
        kycCase.setReferenceId("kyc_" + UUID.randomUUID().toString().replace("-", ""));
        kycCase.setMetadataJson("{}");
        return kycCaseRepository.saveAndFlush(kycCase).getId();
    }
}
