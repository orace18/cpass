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
        assertThat(result.mrzDiagnostics()).containsEntry("finalExtractionSource", "GLOBAL_OCR");
    }

    @Test
    void shouldExtractPassportFieldsFromDedicatedMrzWhenGlobalOcrIsWeak() {
        DocumentAnalysisService.DocumentAnalysisResult result = service.analyze(
                KycDocumentType.PASSPORT,
                """
                        {
                          "ocrReadable": false,
                          "ocrText": "PASSPORT\\nblurred global OCR",
                          "mrzOcrText": "%s\\n%s"
                        }
                        """.formatted(passportMrzLine1(), passportMrzLine2())
        );

        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(result.fullName()).isEqualTo("GRACE DOE");
        assertThat(result.birthDate()).isEqualTo(LocalDate.parse("1990-05-20"));
        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.expiryDate()).isEqualTo(LocalDate.parse("2028-01-10"));
        assertThat(result.confidenceScore()).isGreaterThanOrEqualTo(85);
        assertThat(result.extractedFields())
                .containsEntry("documentNumberExtracted", true)
                .containsEntry("expiryDateExtracted", true)
                .containsEntry("mrzParseSuccess", true)
                .containsEntry("finalExtractionSource", "MRZ_OCR");
    }

    @Test
    void shouldExtractPassportFieldsWhenMrzCheckDigitsAreInvalidButFieldsAreCoherent() {
        DocumentAnalysisService.DocumentAnalysisResult result = service.analyze(
                KycDocumentType.PASSPORT,
                """
                        {
                          "ocrText": "",
                          "mrzOcrText": "%s\\n%s"
                        }
                        """.formatted(passportMrzLine1(), passportMrzLine2().replace("P1234567<1", "P1234567<0"))
        );

        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.expiryDate()).isEqualTo(LocalDate.parse("2028-01-10"));
        assertThat(result.mrzDiagnostics())
                .containsEntry("mrzCandidateDetected", true)
                .containsEntry("mrzParseSuccess", true)
                .containsEntry("checksumStatus", "CHECKSUM_FAILED_ACCEPTED")
                .containsEntry("finalExtractionSource", "MRZ_OCR");
    }

    @Test
    void shouldFallbackToCurrentGlobalOcrBehaviorWhenNoMrzIsPresent() {
        DocumentAnalysisService.DocumentAnalysisResult result = service.analyze(
                KycDocumentType.PASSPORT,
                """
                        {
                          "ocrText": "PASSPORT\\nFull Name: DOE GRACE\\nDate of Birth: 1990-05-20\\nPassport No: P1234567\\nDate of Expiry: 2028-01-10"
                        }
                        """
        );

        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.expiryDate()).isEqualTo(LocalDate.parse("2028-01-10"));
        assertThat(result.mrzDiagnostics())
                .containsEntry("mrzCandidateDetected", false)
                .containsEntry("mrzParseSuccess", false)
                .containsEntry("finalExtractionSource", "GLOBAL_OCR");
    }

    @Test
    void shouldExposeMrzDiagnosticsInResultAndExtractedFields() {
        DocumentAnalysisService.DocumentAnalysisResult result = service.analyze(
                KycDocumentType.PASSPORT,
                """
                        {
                          "ocrText": "PASSPORT",
                          "mrzOcrText": "%s\\n%s"
                        }
                        """.formatted(passportMrzLine1(), passportMrzLine2())
        );

        assertThat(result.mrzDiagnostics())
                .containsKeys(
                        "mrzCandidateDetected",
                        "mrzOcrTextNormalized",
                        "mrzParseSuccess",
                        "documentNumberExtracted",
                        "expiryDateExtracted",
                        "finalExtractionSource"
                );
        assertThat(result.extractedFields()).containsAllEntriesOf(result.mrzDiagnostics());
    }

    private String passportMrzLine1() {
        return padRight("P<BENDOE<<GRACE", 44);
    }

    private String passportMrzLine2() {
        return "P1234567<1BEN9005204F2801108<<<<<<<<<<<<<<<<";
    }

    private String padRight(String value, int length) {
        return value + "<".repeat(length - value.length());
    }
}
