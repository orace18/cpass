package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

@Service
public class DocumentAnalysisService {

    private final ObjectMapper objectMapper;

    public DocumentAnalysisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DocumentAnalysisResult analyze(String metadataJson) {
        Map<String, Object> metadata = parse(metadataJson);
        boolean readable = asBoolean(metadata.getOrDefault("ocrReadable", Boolean.TRUE));
        LocalDate expiryDate = parseDate(metadata.get("expiryDate"));
        String documentNumber = asString(metadata.get("documentNumber"));
        Integer faceMatchScore = asInteger(metadata.get("faceMatchScore"));
        String faceHash = asString(metadata.get("faceHash"));

        KycDocumentValidityStatus validityStatus;
        Integer ocrConfidence;
        if (!readable || expiryDate == null) {
            validityStatus = KycDocumentValidityStatus.UNREADABLE;
            ocrConfidence = 30;
        } else if (expiryDate.isBefore(LocalDate.now())) {
            validityStatus = KycDocumentValidityStatus.EXPIRED;
            ocrConfidence = 60;
        } else {
            validityStatus = KycDocumentValidityStatus.VALID;
            ocrConfidence = documentNumber == null ? 75 : 90;
        }

        return new DocumentAnalysisResult(documentNumber, expiryDate, validityStatus, ocrConfidence, faceHash, faceMatchScore);
    }

    public Map<String, Object> parse(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Collections.emptyMap();
        }
    }

    private LocalDate parseDate(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
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

    private String asString(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public record DocumentAnalysisResult(
            String documentNumber,
            LocalDate expiryDate,
            KycDocumentValidityStatus validityStatus,
            Integer ocrConfidence,
            String faceHash,
            Integer faceMatchScore
    ) {
    }
}
