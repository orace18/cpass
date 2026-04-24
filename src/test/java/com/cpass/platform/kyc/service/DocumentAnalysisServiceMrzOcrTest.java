package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests pour le branchement OCR réel sur OCR_MRZ_READY.
 * Vérifie les 4 scénarios : MRZ valide, engine indisponible, texte bruité, texte invalide.
 */
class DocumentAnalysisServiceMrzOcrTest {

    private final DocumentAnalysisService service = new DocumentAnalysisService(new ObjectMapper());

    // MRZ passeport avec check digits corrects :
    // doc=P1234567< → check=1, dob=900520 → check=4, expiry=280110 → check=8
    private static final String MRZ_LINE_1 = padRight("P<BENDOE<<GRACE", 44);
    private static final String MRZ_LINE_2 = "P1234567<1BEN9005204F2801108<<<<<<<<<<<<<<<<";

    // --- Scénario 1 : OCR engine retourne MRZ valide ---

    @Test
    void ocrEngineRetourneTexteValide_documentNumberEtExpiryDateExtraits() {
        String mrzOcrText = MRZ_LINE_1 + "\n" + MRZ_LINE_2;

        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.PASSPORT, "{}", mrzOcrText);

        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.expiryDate()).isEqualTo(LocalDate.of(2028, 1, 10));
        assertThat(result.fullName()).isEqualTo("GRACE DOE");
        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.VALID);
        assertThat(result.confidenceScore()).isGreaterThanOrEqualTo(85);

        assertThat(result.mrzDiagnostics())
                .containsEntry("mrzParseSuccess", true)
                .containsEntry("documentNumberExtracted", true)
                .containsEntry("expiryDateExtracted", true)
                .containsEntry("mrzOcrTextRawPresent", true)
                .containsEntry("finalExtractionSource", "MRZ_OCR");
    }

    // --- Scénario 2 : OCR engine indisponible (null) → fallback comportement actuel ---

    @Test
    void ocrEngineIndisponible_nullOcrText_fallbackComportementActuel() {
        // Pas de texte OCR ni dans les metadata ni depuis l'engine
        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.NATIONAL_ID, "{}", null);

        assertThat(result.documentNumber()).isNull();
        assertThat(result.expiryDate()).isNull();
        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.UNREADABLE);

        assertThat(result.mrzDiagnostics())
                .containsEntry("mrzParseSuccess", false)
                .containsEntry("mrzOcrTextRawPresent", false)
                .containsEntry("documentNumberExtracted", false)
                .containsEntry("expiryDateExtracted", false);
    }

    @Test
    void ocrEngineIndisponible_metadataContientOcrText_fallbackGlobalOcr() {
        // Fallback : l'engine est null mais les metadata contiennent un ocrText structuré
        String metadataJson = """
                {
                  "ocrText": "PASSPORT\\nFull Name: DOE GRACE\\nPassport No: P9876543\\nDate of Expiry: 2030-06-15"
                }
                """;

        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.PASSPORT, metadataJson, null);

        assertThat(result.documentNumber()).isEqualTo("P9876543");
        assertThat(result.expiryDate()).isEqualTo(LocalDate.of(2030, 6, 15));
        assertThat(result.mrzDiagnostics())
                .containsEntry("finalExtractionSource", "GLOBAL_OCR")
                .containsEntry("mrzParseSuccess", false);
    }

    // --- Scénario 3 : OCR texte bruité mais normalisable → parsing OK ---

    @Test
    void ocrTexteEnMajusculesAvecEspacesNormalisables_parsingOk() {
        // Tesseract sort parfois lowercase ou avec des espaces parasites
        String noisyLine1 = MRZ_LINE_1.toLowerCase();  // lowercase → normalisé en uppercase
        String noisyLine2 = "P 1234567<1BEN9005204F2801108<<<<<<<<<<<<<<<<"; // espace parasite → < après normalisation

        String mrzOcrText = noisyLine1 + "\n" + noisyLine2;

        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.PASSPORT, "{}", mrzOcrText);

        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.expiryDate()).isEqualTo(LocalDate.of(2028, 1, 10));
        assertThat(result.mrzDiagnostics())
                .containsEntry("mrzParseSuccess", true)
                .containsEntry("documentNumberExtracted", true)
                .containsEntry("expiryDateExtracted", true);
    }

    @Test
    void ocrTexteAvecCharacteresParasites_filtresParNormalisation_parsingOk() {
        // Tesseract peut produire des caractères non-MRZ filtrés par replaceAll("[^A-Z0-9<]", "")
        String noisyLine1 = MRZ_LINE_1.replace("<", " < ");  // espaces autour des <
        String noisyLine2 = MRZ_LINE_2.replace("<", " < ");

        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.PASSPORT, "{}", noisyLine1 + "\n" + noisyLine2);

        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.mrzDiagnostics()).containsEntry("mrzParseSuccess", true);
    }

    // --- Scénario 4 : OCR texte invalide → reste UNREADABLE ---

    @Test
    void ocrTexteInvalide_resteUnreadable() {
        String invalidOcrText = "RANDOM GARBAGE TEXT\nNOT A VALID MRZ FORMAT AT ALL";

        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.PASSPORT, "{}", invalidOcrText);

        assertThat(result.documentNumber()).isNull();
        assertThat(result.expiryDate()).isNull();
        assertThat(result.validityStatus()).isEqualTo(KycDocumentValidityStatus.UNREADABLE);
        assertThat(result.mrzDiagnostics())
                .containsEntry("mrzParseSuccess", false)
                .containsEntry("documentNumberExtracted", false)
                .containsEntry("expiryDateExtracted", false);
    }

    @Test
    void ocrTexteAvecCheckDigitIncorrect_resteUnreadable() {
        // Check digit corrompu : position 9 changée de '1' à '0'
        String corruptLine2 = MRZ_LINE_2.replace("P1234567<1", "P1234567<0");

        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.PASSPORT, "{}", MRZ_LINE_1 + "\n" + corruptLine2);

        assertThat(result.documentNumber()).isNull();
        assertThat(result.mrzDiagnostics())
                .containsEntry("mrzCandidateDetected", true)
                .containsEntry("mrzParseSuccess", false)
                .containsEntry("finalExtractionSource", "FALLBACK");
    }

    // --- Priorité : engine OCR prime sur mrzOcrText dans les metadata ---

    @Test
    void engineOcrPrimeSurMrzOcrTextDansMetadata() {
        // Les metadata contiennent un MRZ invalide (check digit corrompu)
        String metadataWithBadMrz = """
                {
                  "mrzOcrText": "%s\\n%s"
                }
                """.formatted(MRZ_LINE_1, MRZ_LINE_2.replace("P1234567<1", "P1234567<0"));

        // Mais l'engine fournit le bon texte MRZ
        String engineOcrText = MRZ_LINE_1 + "\n" + MRZ_LINE_2;

        DocumentAnalysisService.DocumentAnalysisResult result =
                service.analyze(KycDocumentType.PASSPORT, metadataWithBadMrz, engineOcrText);

        // Le texte de l'engine (valide) doit prendre la priorité
        assertThat(result.documentNumber()).isEqualTo("P1234567");
        assertThat(result.mrzDiagnostics()).containsEntry("mrzParseSuccess", true);
    }

    private static String padRight(String value, int length) {
        return value + "<".repeat(length - value.length());
    }
}
