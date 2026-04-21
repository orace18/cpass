package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentAnalysisService {

    private static final List<String> FULL_NAME_KEYS = List.of("fullName", "name", "holderName", "documentFullName");
    private static final List<String> BIRTH_DATE_KEYS = List.of("birthDate", "dateOfBirth", "dob");
    private static final List<String> DOCUMENT_NUMBER_KEYS = List.of(
            "documentNumber",
            "passportNumber",
            "nationalIdNumber",
            "voterCardNumber",
            "driverLicenseNumber",
            "licenseNumber",
            "idNumber"
    );
    private static final List<String> EXPIRY_DATE_KEYS = List.of("expiryDate", "expirationDate", "dateOfExpiry", "validUntil");
    private static final List<String> TEXT_KEYS = List.of("ocrText", "rawText", "documentText", "text", "mrz");
    private static final Pattern DOCUMENT_NUMBER_PATTERN = Pattern.compile(
            "(?i)(passport|document|doc|national\\s*id|id|voter|license|licence)\\s*(no\\.?|number|n[o°]?|#)?\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-]{4,})"
    );
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})");
    private static final Pattern FULL_NAME_PATTERN = Pattern.compile("(?im)^(full\\s*name|name|holder\\s*name)\\s*[:\\-]\\s*(.+)$");

    private final ObjectMapper objectMapper;

    public DocumentAnalysisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DocumentAnalysisResult analyze(String metadataJson) {
        return analyze(null, metadataJson);
    }

    public DocumentAnalysisResult analyze(KycDocumentType documentType, String metadataJson) {
        Map<String, Object> metadata = parse(metadataJson);
        boolean readable = asBoolean(metadata.getOrDefault("ocrReadable", Boolean.TRUE));
        KycDocumentType detectedDocumentType = detectDocumentType(documentType, metadata);
        boolean supportedDocumentType = documentType == null
                || isMediaDocument(documentType)
                || isSupportedIdentityDocument(documentType);

        List<Map<String, Object>> fieldSources = fieldSources(metadata);
        String ocrText = firstString(metadata, TEXT_KEYS);
        Map<String, String> mrzFields = parseMrz(ocrText);

        String fullName = firstNonBlank(firstString(fieldSources, FULL_NAME_KEYS), mrzFields.get("fullName"), extractFullName(ocrText));
        LocalDate birthDate = firstNonNull(firstDate(fieldSources, BIRTH_DATE_KEYS), parseDate(mrzFields.get("birthDate")), extractDate(ocrText, "birth"));
        String documentNumber = firstNonBlank(firstString(fieldSources, DOCUMENT_NUMBER_KEYS), mrzFields.get("documentNumber"), extractDocumentNumber(ocrText));
        LocalDate expiryDate = firstNonNull(firstDate(fieldSources, EXPIRY_DATE_KEYS), parseDate(mrzFields.get("expiryDate")), extractDate(ocrText, "expiry"));
        Integer faceMatchScore = asInteger(metadata.get("faceMatchScore"));
        String faceHash = asString(metadata.get("faceHash"));
        Map<String, Object> extractedFields = extractedFields(detectedDocumentType, supportedDocumentType, fullName, birthDate, documentNumber, expiryDate);

        KycDocumentValidityStatus validityStatus;
        Integer confidenceScore;
        if (!readable) {
            validityStatus = KycDocumentValidityStatus.UNREADABLE;
            confidenceScore = 30;
        } else if (isMediaDocument(documentType)) {
            validityStatus = KycDocumentValidityStatus.VALID;
            confidenceScore = 90;
        } else if (!supportedDocumentType) {
            validityStatus = KycDocumentValidityStatus.UNREADABLE;
            confidenceScore = 20;
        } else if (expiryDate != null && expiryDate.isBefore(LocalDate.now())) {
            validityStatus = KycDocumentValidityStatus.EXPIRED;
            confidenceScore = 60;
        } else if (!hasReadableIdentitySignal(fullName, birthDate, documentNumber, expiryDate, ocrText)) {
            validityStatus = KycDocumentValidityStatus.UNREADABLE;
            confidenceScore = 30;
        } else {
            validityStatus = KycDocumentValidityStatus.VALID;
            confidenceScore = confidenceScore(fullName, birthDate, documentNumber, expiryDate, ocrText);
        }

        return new DocumentAnalysisResult(
                detectedDocumentType,
                supportedDocumentType,
                fullName,
                birthDate,
                documentNumber,
                expiryDate,
                validityStatus,
                confidenceScore,
                extractedFields,
                faceHash,
                faceMatchScore
        );
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
        String raw = String.valueOf(value).trim();
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy")
        )) {
            try {
                return LocalDate.parse(raw, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        Matcher matcher = DATE_PATTERN.matcher(raw);
        if (matcher.find()) {
            return parseDate(matcher.group(1));
        }
        return null;
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

    private KycDocumentType detectDocumentType(KycDocumentType requestedDocumentType, Map<String, Object> metadata) {
        String explicitType = firstNonBlank(
                asString(metadata.get("detectedDocumentType")),
                asString(metadata.get("documentType")),
                asString(metadata.get("type"))
        );
        Optional<KycDocumentType> explicit = parseDocumentType(explicitType);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        String text = firstString(metadata, TEXT_KEYS);
        String normalizedText = text == null ? "" : text.toUpperCase(Locale.ROOT);
        if (normalizedText.contains("PASSPORT") || normalizedText.contains("P<")) {
            return KycDocumentType.PASSPORT;
        }
        if (normalizedText.contains("VOTER")) {
            return KycDocumentType.VOTER_CARD;
        }
        if (normalizedText.contains("DRIVER") || normalizedText.contains("LICENCE") || normalizedText.contains("LICENSE")) {
            return KycDocumentType.DRIVER_LICENSE;
        }
        if (normalizedText.contains("NATIONAL ID") || normalizedText.contains("IDENTITY CARD")) {
            return KycDocumentType.NATIONAL_ID;
        }
        return requestedDocumentType == KycDocumentType.ID_CARD ? KycDocumentType.NATIONAL_ID : requestedDocumentType;
    }

    private Optional<KycDocumentType> parseDocumentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (normalized.equals("NATIONAL_ID_CARD") || normalized.equals("IDENTITY_CARD")) {
            normalized = "NATIONAL_ID";
        }
        if (normalized.equals("DRIVING_LICENSE") || normalized.equals("DRIVING_LICENCE") || normalized.equals("DRIVER_LICENCE")) {
            normalized = "DRIVER_LICENSE";
        }
        try {
            return Optional.of(KycDocumentType.valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean isMediaDocument(KycDocumentType documentType) {
        return documentType == KycDocumentType.SELFIE || documentType == KycDocumentType.FIELD_AGENT_PHOTO;
    }

    private boolean isSupportedIdentityDocument(KycDocumentType documentType) {
        return documentType == KycDocumentType.PASSPORT
                || documentType == KycDocumentType.NATIONAL_ID
                || documentType == KycDocumentType.ID_CARD
                || documentType == KycDocumentType.VOTER_CARD
                || documentType == KycDocumentType.DRIVER_LICENSE;
    }

    private List<Map<String, Object>> fieldSources(Map<String, Object> metadata) {
        return List.of(
                metadata,
                asMap(metadata.get("extractedFields")),
                asMap(metadata.get("document")),
                asMap(metadata.get("ocr")),
                asMap(metadata.get("fields"))
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private String firstString(List<Map<String, Object>> sources, List<String> keys) {
        for (Map<String, Object> source : sources) {
            String value = firstString(source, keys);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstString(Map<String, Object> source, List<String> keys) {
        for (String key : keys) {
            String value = asString(source.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private LocalDate firstDate(List<Map<String, Object>> sources, List<String> keys) {
        for (Map<String, Object> source : sources) {
            for (String key : keys) {
                LocalDate value = parseDate(source.get(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String extractDocumentNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = DOCUMENT_NUMBER_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(3) : null;
    }

    private String extractFullName(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = FULL_NAME_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(2).trim();
        return value.isBlank() ? null : value;
    }

    private LocalDate extractDate(String text, String kind) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String labelPattern = kind.equals("birth")
                ? "(?i)(date\\s*of\\s*birth|birth\\s*date|dob)"
                : "(?i)(date\\s*of\\s*expiry|expiry\\s*date|expires|expiration\\s*date|valid\\s*until|exp)";
        Matcher matcher = Pattern.compile(labelPattern + "\\s*[:\\-]?\\s*" + DATE_PATTERN.pattern()).matcher(text);
        return matcher.find() ? parseDate(matcher.group(matcher.groupCount())) : null;
    }

    private Map<String, String> parseMrz(String text) {
        if (text == null || text.isBlank() || !text.contains("<")) {
            return Collections.emptyMap();
        }
        String[] lines = text.lines()
                .map(String::trim)
                .filter(line -> line.contains("<"))
                .toArray(String[]::new);
        if (lines.length < 2 || !lines[0].startsWith("P<")) {
            return Collections.emptyMap();
        }

        Map<String, String> fields = new LinkedHashMap<>();
        String namePart = lines[0].length() > 5 ? lines[0].substring(5) : "";
        String[] names = namePart.split("<<", 2);
        String surname = names.length > 0 ? names[0].replace('<', ' ').trim() : "";
        String givenNames = names.length > 1 ? names[1].replace('<', ' ').trim() : "";
        String fullName = (givenNames + " " + surname).trim().replaceAll("\\s+", " ");
        if (!fullName.isBlank()) {
            fields.put("fullName", fullName);
        }

        String secondLine = lines[1];
        if (secondLine.length() >= 9) {
            fields.put("documentNumber", secondLine.substring(0, 9).replace("<", "").trim());
        }
        if (secondLine.length() >= 19) {
            LocalDate birthDate = parseMrzDate(secondLine.substring(13, 19), false);
            if (birthDate != null) {
                fields.put("birthDate", birthDate.toString());
            }
        }
        if (secondLine.length() >= 27) {
            LocalDate expiryDate = parseMrzDate(secondLine.substring(21, 27), true);
            if (expiryDate != null) {
                fields.put("expiryDate", expiryDate.toString());
            }
        }
        return fields;
    }

    private LocalDate parseMrzDate(String raw, boolean expiry) {
        if (raw == null || !raw.matches("\\d{6}")) {
            return null;
        }
        int year = Integer.parseInt(raw.substring(0, 2));
        int month = Integer.parseInt(raw.substring(2, 4));
        int day = Integer.parseInt(raw.substring(4, 6));
        int fullYear = 2000 + year;
        int currentYear = LocalDate.now().getYear();
        if (!expiry && fullYear > currentYear) {
            fullYear -= 100;
        }
        if (expiry && fullYear < currentYear - 5) {
            fullYear += 100;
        }
        try {
            return LocalDate.of(fullYear, month, day);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private boolean hasReadableIdentitySignal(String fullName,
                                              LocalDate birthDate,
                                              String documentNumber,
                                              LocalDate expiryDate,
                                              String ocrText) {
        return fullName != null
                || birthDate != null
                || documentNumber != null
                || expiryDate != null
                || (ocrText != null && !ocrText.isBlank());
    }

    private int confidenceScore(String fullName,
                                LocalDate birthDate,
                                String documentNumber,
                                LocalDate expiryDate,
                                String ocrText) {
        int score = 35;
        if (ocrText != null && !ocrText.isBlank()) {
            score += 10;
        }
        if (documentNumber != null) {
            score += 20;
        }
        if (expiryDate != null) {
            score += 20;
        }
        if (fullName != null) {
            score += 20;
        }
        if (birthDate != null) {
            score += 10;
        }
        return Math.min(95, score);
    }

    private Map<String, Object> extractedFields(KycDocumentType detectedDocumentType,
                                                boolean supportedDocumentType,
                                                String fullName,
                                                LocalDate birthDate,
                                                String documentNumber,
                                                LocalDate expiryDate) {
        Map<String, Object> fields = new LinkedHashMap<>();
        putIfPresent(fields, "detectedDocumentType", detectedDocumentType == null ? null : detectedDocumentType.name());
        fields.put("supportedDocumentType", supportedDocumentType);
        putIfPresent(fields, "fullName", fullName);
        putIfPresent(fields, "birthDate", birthDate == null ? null : birthDate.toString());
        putIfPresent(fields, "documentNumber", documentNumber);
        putIfPresent(fields, "expiryDate", expiryDate == null ? null : expiryDate.toString());
        return fields;
    }

    private void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            fields.put(key, value);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record DocumentAnalysisResult(
            KycDocumentType detectedDocumentType,
            boolean supportedDocumentType,
            String fullName,
            LocalDate birthDate,
            String documentNumber,
            LocalDate expiryDate,
            KycDocumentValidityStatus validityStatus,
            Integer confidenceScore,
            Map<String, Object> extractedFields,
            String faceHash,
            Integer faceMatchScore
    ) {
    }
}
