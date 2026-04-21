package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SelfieAnalysisServiceTest {

    private final SelfieAnalysisService service = new SelfieAnalysisService(new DocumentAnalysisService(new ObjectMapper()));

    @Test
    void shouldAcceptSelfieWithStrongFaceMatchSignals() {
        SelfieAnalysisService.SelfieAnalysisResult result = service.analyze(
                "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":92,\"faceDetectedOnDocument\":true,\"faceMatchScore\":88}"
        );

        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(result.faceDecision()).isEqualTo("MATCH");
        assertThat(result.biometricConfidenceScore()).isEqualTo(90);
        assertThat(result.biometricSignals()).containsEntry("faceDetectedOnSelfie", true);
        assertThat(result.biometricSignals()).containsEntry("faceDetectedOnDocument", true);
    }

    @Test
    void shouldMarkSelfieAsUnreadableWhenQualityIsTooLow() {
        SelfieAnalysisService.SelfieAnalysisResult result = service.analyze(
                "{\"faceDetectedOnSelfie\":true,\"selfieQuality\":45,\"faceMatchScore\":90}"
        );

        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.UNREADABLE);
        assertThat(result.faceDecision()).isEqualTo("SELFIE_UNUSABLE");
        assertThat(result.biometricConfidenceScore()).isEqualTo(30);
    }
}
