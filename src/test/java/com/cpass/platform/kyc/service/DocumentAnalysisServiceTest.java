package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAnalysisServiceTest {

    private final DocumentAnalysisService service = new DocumentAnalysisService(new ObjectMapper());

    @Test
    void shouldExtractPassportFieldsFromOcrTextPayload() {
        DocumentAnalysisService.DocumentAnalysisResult result = service.analyze(
                KycDocumentType.PASSPORT,
                """
                        {
                          "ocrText": "PASSPORT\\nFull Name: DOE GRACE\\nDate of Birth: 1990-05-20\\nPassport No: P1234567\\nDate of Expiry: 2028-01-10"
                        }
                        """
        );

        assertThat(result.detectedDocumentType()).isEqualTo(KycDocumentType.PASSPORT);
        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(result.fullName()).isEqualTo("DOE GRACE");
        assertThat(result.birthDate()).isEqualTo(LocalDate.parse("1990-05-20"));
        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.expiryDate()).isEqualTo(LocalDate.parse("2028-01-10"));
        assertThat(result.confidenceScore()).isGreaterThanOrEqualTo(90);
        assertThat(result.extractedFields()).containsEntry("supportedDocumentType", true);
    }
}
