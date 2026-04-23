package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.*;
import com.cpass.platform.kyc.dto.*;
import com.cpass.platform.kyc.infra.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class KycVerifyMvpTest {

    @Autowired
    private KycService kycService;
    @Autowired
    private KycCaseRepository kycCaseRepository;
    @Autowired
    private KycDocumentRepository kycDocumentRepository;
    @Autowired
    private KycFlowService kycFlowService;

    @Test
    public void testVerifyMvpFlow() throws Exception {
        UUID clientId = UUID.randomUUID();
        KycCheckOrInitRequest initReq = new KycCheckOrInitRequest(
                "user-123",
                "John Doe",
                LocalDate.of(1990, 1, 1),
                "+33600000000",
                "123456789",
                "hash123",
                KycLevel.LEVEL_1,
                true
        );

        KycCheckOrInitResponse init = kycService.checkOrInit(clientId, KycLevel.LEVEL_1, initReq);
        
        // Emulate consumer for checkOrInit
        kycFlowService.process(init.kycCaseId());

        MockMultipartFile document = new MockMultipartFile("document", "doc.jpg", "image/jpeg", "fake-image".getBytes());
        kycService.upload(clientId, init.kycCaseId(), KycDocumentType.NATIONAL_ID, document, "{\"ocrReadable\":true}");
        
        // Emulate consumer for upload doc
        kycFlowService.process(init.kycCaseId());

        MockMultipartFile selfie = new MockMultipartFile("selfie", "selfie.jpg", "image/jpeg", "fake-image".getBytes());
        kycService.upload(clientId, init.kycCaseId(), KycDocumentType.SELFIE, selfie, "{}");
        
        // Emulate consumer for upload selfie
        kycFlowService.process(init.kycCaseId());

        KycCaseEntity kycCase = kycCaseRepository.findById(init.kycCaseId()).orElseThrow();
        System.out.println("FINAL STATUS: " + kycCase.getStatus());
        System.out.println("FINAL REASON: " + kycCase.getStatusReason());
        
        List<KycDocumentEntity> docs = kycDocumentRepository.findByKycCaseOrderByCreatedAtAsc(kycCase);
        System.out.println("DOCS COUNT: " + docs.size());
        
        assertThat(kycCase.getStatus()).isNotEqualTo(KycCaseStatus.INITIATED);
    }
}
