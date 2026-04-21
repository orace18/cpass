package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SelfieAnalysisService {

    static final int MIN_SELFIE_QUALITY_SCORE = 60;
    static final int STRONG_FACE_MATCH_SCORE = 80;
    static final int INCONCLUSIVE_FACE_MATCH_SCORE = 60;

    static final String FACE_DECISION_MATCH = "MATCH";
    static final String FACE_DECISION_INCONCLUSIVE = "INCONCLUSIVE";
    static final String FACE_DECISION_MISMATCH = "MISMATCH";
    static final String FACE_DECISION_SELFIE_UNUSABLE = "SELFIE_UNUSABLE";

    private static final List<String> FACE_DETECTED_SELFIE_KEYS = List.of("faceDetectedOnSelfie", "selfieFaceDetected", "faceDetected");
    private static final List<String> SELFIE_READABILITY_KEYS = List.of("selfieReadability", "selfieReadable", "ocrReadable", "readable");
    private static final List<String> SELFIE_QUALITY_KEYS = List.of("selfieQuality", "selfieQualityScore", "qualityScore", "imageQualityScore");
    private static final List<String> FACE_DETECTED_DOCUMENT_KEYS = List.of("faceDetectedOnDocument", "documentFaceDetected");
    private static final List<String> FACE_MATCH_SCORE_KEYS = List.of("faceMatchScore", "biometricMatchScore");

    private final DocumentAnalysisService documentAnalysisService;

    public SelfieAnalysisService(DocumentAnalysisService documentAnalysisService) {
        this.documentAnalysisService = documentAnalysisService;
    }

    public SelfieAnalysisResult analyze(String metadataJson) {
        Map<String, Object> metadata = documentAnalysisService.parse(metadataJson);
        List<Map<String, Object>> sources = fieldSources(metadata);

        Boolean selfieReadability = firstBoolean(sources, SELFIE_READABILITY_KEYS);
        Integer faceMatchScore = firstInteger(sources, FACE_MATCH_SCORE_KEYS);
        Boolean faceDetectedOnSelfie = firstBoolean(sources, FACE_DETECTED_SELFIE_KEYS);
        Boolean faceDetectedOnDocument = firstBoolean(sources, FACE_DETECTED_DOCUMENT_KEYS);
        Integer selfieQuality = firstInteger(sources, SELFIE_QUALITY_KEYS);

        if (selfieReadability == null) {
            selfieReadability = true;
        }
        if (faceMatchScore != null) {
            faceDetectedOnSelfie = faceDetectedOnSelfie == null ? true : faceDetectedOnSelfie;
            faceDetectedOnDocument = faceDetectedOnDocument == null ? true : faceDetectedOnDocument;
        }
        if (selfieQuality == null) {
            selfieQuality = Boolean.FALSE.equals(selfieReadability) ? 30 : 70;
        }

        boolean unusable = Boolean.FALSE.equals(selfieReadability)
                || Boolean.FALSE.equals(faceDetectedOnSelfie)
                || selfieQuality < MIN_SELFIE_QUALITY_SCORE;

        String faceDecision = faceDecision(unusable, faceDetectedOnDocument, faceMatchScore);
        Integer biometricConfidenceScore = biometricConfidenceScore(unusable, selfieQuality, faceMatchScore);
        KycDocumentValidityStatus validityStatus = unusable
                ? KycDocumentValidityStatus.UNREADABLE
                : KycDocumentValidityStatus.VALID;

        Map<String, Object> signals = new LinkedHashMap<>();
        putIfPresent(signals, "faceDetectedOnSelfie", faceDetectedOnSelfie);
        signals.put("selfieReadability", selfieReadability);
        signals.put("selfieQuality", selfieQuality);
        putIfPresent(signals, "faceDetectedOnDocument", faceDetectedOnDocument);
        putIfPresent(signals, "faceMatchScore", faceMatchScore);
        signals.put("faceDecision", faceDecision);
        signals.put("biometricConfidenceScore", biometricConfidenceScore);

        return new SelfieAnalysisResult(
                faceDetectedOnSelfie,
                selfieReadability,
                selfieQuality,
                faceDetectedOnDocument,
                faceMatchScore,
                faceDecision,
                biometricConfidenceScore,
                validityStatus,
                signals
        );
    }

    private String faceDecision(boolean unusable, Boolean faceDetectedOnDocument, Integer faceMatchScore) {
        if (unusable) {
            return FACE_DECISION_SELFIE_UNUSABLE;
        }
        if (Boolean.FALSE.equals(faceDetectedOnDocument)) {
            return FACE_DECISION_INCONCLUSIVE;
        }
        if (faceMatchScore == null || faceMatchScore >= INCONCLUSIVE_FACE_MATCH_SCORE && faceMatchScore < STRONG_FACE_MATCH_SCORE) {
            return FACE_DECISION_INCONCLUSIVE;
        }
        if (faceMatchScore >= STRONG_FACE_MATCH_SCORE) {
            return FACE_DECISION_MATCH;
        }
        return FACE_DECISION_MISMATCH;
    }

    private Integer biometricConfidenceScore(boolean unusable, Integer selfieQuality, Integer faceMatchScore) {
        if (unusable) {
            return Math.min(selfieQuality, 30);
        }
        if (faceMatchScore == null) {
            return selfieQuality;
        }
        return Math.round((selfieQuality + faceMatchScore) / 2.0f);
    }

    private List<Map<String, Object>> fieldSources(Map<String, Object> metadata) {
        return List.of(
                metadata,
                asMap(metadata.get("extractedFields")),
                asMap(metadata.get("biometricSignals")),
                asMap(metadata.get("signals"))
        );
    }

    private Boolean firstBoolean(List<Map<String, Object>> sources, List<String> keys) {
        for (Map<String, Object> source : sources) {
            for (String key : keys) {
                Boolean value = asBoolean(source.get(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private Integer firstInteger(List<Map<String, Object>> sources, List<String> keys) {
        for (Map<String, Object> source : sources) {
            for (String key : keys) {
                Integer value = asInteger(source.get(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "yes", "y", "1", "readable", "clear", "good" -> true;
            case "false", "no", "n", "0", "unreadable", "blurred", "blurry", "poor" -> false;
            default -> null;
        };
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void putIfPresent(Map<String, Object> signals, String key, Object value) {
        if (value != null) {
            signals.put(key, value);
        }
    }

    public record SelfieAnalysisResult(
            Boolean faceDetectedOnSelfie,
            Boolean selfieReadability,
            Integer selfieQuality,
            Boolean faceDetectedOnDocument,
            Integer faceMatchScore,
            String faceDecision,
            Integer biometricConfidenceScore,
            KycDocumentValidityStatus validityStatus,
            Map<String, Object> biometricSignals
    ) {
    }
}
