package com.cpass.platform.kyc.service;

import com.cpass.platform.common.security.SecurityMetricsService;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycDocumentStatus;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.domain.KycDocumentValidityStatus;
import com.cpass.platform.kyc.domain.KycReasonCode;
import com.cpass.platform.kyc.dto.KycOperationalMetricsResponse;
import com.cpass.platform.kyc.infra.KycCaseRepository;
import com.cpass.platform.kyc.infra.KycDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class KycOperationalMetricsService {

    private final KycCaseRepository kycCaseRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final SecurityMetricsService securityMetricsService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public KycOperationalMetricsService(KycCaseRepository kycCaseRepository,
                                        KycDocumentRepository kycDocumentRepository,
                                        SecurityMetricsService securityMetricsService,
                                        MeterRegistry meterRegistry,
                                        ObjectMapper objectMapper) {
        this.kycCaseRepository = kycCaseRepository;
        this.kycDocumentRepository = kycDocumentRepository;
        this.securityMetricsService = securityMetricsService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("kyc.cases.total", kycCaseRepository, KycCaseRepository::count)
                .description("Total KYC cases persisted")
                .register(meterRegistry);
        for (KycCaseStatus status : KycCaseStatus.values()) {
            Gauge.builder("kyc.cases.status", () -> kycCaseRepository.countByStatus(status))
                    .description("KYC cases by internal case status")
                    .tag("caseStatus", status.name())
                    .tag("productStatus", productStatus(status))
                    .register(meterRegistry);
        }
    }

    public void recordCaseDecision(KycCaseEntity kycCase, KycDocumentEntity primaryDocument) {
        KycReasonCode reasonCode = reasonCode(kycCase, primaryDocument);
        String caseStatus = kycCase.getStatus() == null ? "UNKNOWN" : kycCase.getStatus().name();
        String productStatus = productStatus(kycCase.getStatus());
        String reason = reasonCode == null ? "NONE" : reasonCode.name();
        String documentType = primaryDocument == null || primaryDocument.getDocumentType() == null
                ? "NONE"
                : primaryDocument.getDocumentType().name();

        meterRegistry.counter(
                "kyc.case.decisions",
                "caseStatus", caseStatus,
                "productStatus", productStatus,
                "reasonCode", reason,
                "documentType", documentType
        ).increment();

        processingDuration(kycCase).ifPresent(duration -> meterRegistry.timer(
                "kyc.case.processing",
                "caseStatus", caseStatus,
                "productStatus", productStatus
        ).record(duration));
    }

    @Transactional(readOnly = true)
    public KycOperationalMetricsResponse snapshot() {
        List<KycCaseEntity> cases = kycCaseRepository.findAll();
        List<KycDocumentEntity> documents = kycDocumentRepository.findAll();
        Map<UUID, List<KycDocumentEntity>> documentsByCase = documents.stream()
                .filter(document -> document.getKycCase() != null)
                .collect(Collectors.groupingBy(document -> document.getKycCase().getId()));

        long totalCases = cases.size();
        Map<KycCaseStatus, Long> statusCounts = new EnumMap<>(KycCaseStatus.class);
        for (KycCaseStatus status : KycCaseStatus.values()) {
            statusCounts.put(status, 0L);
        }
        cases.forEach(kycCase -> statusCounts.merge(kycCase.getStatus(), 1L, Long::sum));

        Map<String, Long> caseStatusCounts = new LinkedHashMap<>();
        Map<String, Double> caseStatusRates = new LinkedHashMap<>();
        statusCounts.forEach((status, count) -> {
            String key = status.name();
            caseStatusCounts.put(key, count);
            caseStatusRates.put(key, rate(count, totalCases));
        });

        Map<String, Long> reasonCounts = cases.stream()
                .map(kycCase -> reasonCode(kycCase, latestIdentityDocument(documentsByCase.get(kycCase.getId())).orElse(null)))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(KycReasonCode::name, LinkedHashMap::new, Collectors.counting()));

        double avgProcessingTimeMillis = cases.stream()
                .map(this::processingMillis)
                .flatMap(Optional::stream)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0D);

        SecurityMetricsService.AuthFailureSnapshot authSnapshot = securityMetricsService.authFailureSnapshot();

        return new KycOperationalMetricsResponse(
                Instant.now(),
                totalCases,
                outcome(statusCounts, KycCaseStatus.APPROVED, totalCases),
                outcome(statusCounts, KycCaseStatus.RESUBMISSION_REQUIRED, totalCases),
                outcome(statusCounts, KycCaseStatus.REVIEW, totalCases),
                outcome(statusCounts, KycCaseStatus.REJECTED, totalCases),
                caseStatusCounts,
                caseStatusRates,
                topCounts(reasonCounts, totalCases, 8),
                round(avgProcessingTimeMillis),
                new KycOperationalMetricsResponse.AuthFailureMetric(
                        authSnapshot.total(),
                        authSnapshot.byCode(),
                        authSnapshot.byMode()
                ),
                friction(reasonCounts, documents),
                v2Priorities(statusCounts, reasonCounts, documents, authSnapshot.total())
        );
    }

    KycReasonCode reasonCode(KycCaseEntity kycCase, KycDocumentEntity primaryDocument) {
        KycCaseStatus caseStatus = kycCase.getStatus();
        String normalizedReason = kycCase.getStatusReason() == null
                ? ""
                : kycCase.getStatusReason().toLowerCase(Locale.ROOT);

        if (caseStatus == KycCaseStatus.RESUBMISSION_REQUIRED) {
            if (normalizedReason.contains("biometric") || normalizedReason.contains("face match")) {
                return KycReasonCode.BIOMETRIC_MISMATCH;
            }
            if (normalizedReason.contains("selfie")) {
                return KycReasonCode.SELFIE_UNREADABLE;
            }
            if (normalizedReason.contains("declared")) {
                return KycReasonCode.DECLARED_INFO_MISMATCH;
            }
            if (normalizedReason.contains("unsupported")) {
                return KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE;
            }
            return KycReasonCode.DOCUMENT_UNREADABLE;
        }
        if (caseStatus == KycCaseStatus.REJECTED && normalizedReason.contains("expired")) {
            return KycReasonCode.DOCUMENT_EXPIRED;
        }
        if (caseStatus == KycCaseStatus.REVIEW) {
            return KycReasonCode.MANUAL_REVIEW_REQUIRED;
        }
        if (primaryDocument != null && primaryDocument.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE) {
            return primaryDocument.getDocumentType() == KycDocumentType.SELFIE
                    ? KycReasonCode.SELFIE_UNREADABLE
                    : KycReasonCode.DOCUMENT_UNREADABLE;
        }
        return null;
    }

    private KycOperationalMetricsResponse.FrictionMetric friction(Map<String, Long> reasonCounts,
                                                                  List<KycDocumentEntity> documents) {
        List<KycOperationalMetricsResponse.CountMetric> topUnreadableCauses = topCounts(
                reasonCounts.entrySet().stream()
                        .filter(entry -> entry.getKey().contains("UNREADABLE") || entry.getKey().contains("UNSUPPORTED"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                reasonCounts.values().stream().mapToLong(Long::longValue).sum(),
                5
        );

        Map<String, Long> selfieFailureCauses = selfieFailureCauses(documents);
        long mismatchCount = reasonCounts.getOrDefault(KycReasonCode.DECLARED_INFO_MISMATCH.name(), 0L);
        List<KycOperationalMetricsResponse.DocumentPerformanceMetric> leastPerforming = documentPerformance(documents).stream()
                .sorted(Comparator.comparing(KycOperationalMetricsResponse.DocumentPerformanceMetric::failureRate).reversed())
                .limit(5)
                .toList();

        return new KycOperationalMetricsResponse.FrictionMetric(
                topUnreadableCauses,
                selfieFailureCauses,
                mismatchCount,
                leastPerforming
        );
    }

    private Map<String, Long> selfieFailureCauses(List<KycDocumentEntity> documents) {
        Map<String, Long> causes = new LinkedHashMap<>();
        causes.put("NO_FACE_DETECTED", 0L);
        causes.put("LOW_QUALITY", 0L);
        causes.put("INCONCLUSIVE_FACE_MATCH", 0L);
        causes.put("UNREADABLE_SELFIE", 0L);

        documents.stream()
                .filter(document -> document.getDocumentType() == KycDocumentType.SELFIE)
                .forEach(document -> {
                    Map<String, Object> metadata = metadata(document);
                    if (document.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE) {
                        causes.merge("UNREADABLE_SELFIE", 1L, Long::sum);
                    }
                    Object faceDetected = metadata.get("faceDetectedOnSelfie");
                    if (Boolean.FALSE.equals(faceDetected)) {
                        causes.merge("NO_FACE_DETECTED", 1L, Long::sum);
                    }
                    Integer selfieQuality = asInteger(metadata.get("selfieQuality"));
                    if (selfieQuality != null && selfieQuality < 60) {
                        causes.merge("LOW_QUALITY", 1L, Long::sum);
                    }
                    String faceDecision = asString(metadata.get("faceDecision"));
                    if (faceDecision != null && !SelfieAnalysisService.FACE_DECISION_MATCH.equals(faceDecision)) {
                        causes.merge("INCONCLUSIVE_FACE_MATCH", 1L, Long::sum);
                    }
                });
        return causes;
    }

    private List<KycOperationalMetricsResponse.DocumentPerformanceMetric> documentPerformance(List<KycDocumentEntity> documents) {
        return documents.stream()
                .filter(document -> document.getDocumentType() != null)
                .collect(Collectors.groupingBy(KycDocumentEntity::getDocumentType))
                .entrySet()
                .stream()
                .map(entry -> documentPerformance(entry.getKey(), entry.getValue()))
                .toList();
    }

    private KycOperationalMetricsResponse.DocumentPerformanceMetric documentPerformance(KycDocumentType type,
                                                                                       List<KycDocumentEntity> documents) {
        long total = documents.size();
        long approved = documents.stream().filter(document -> document.getStatus() == KycDocumentStatus.APPROVED).count();
        long resubmission = documents.stream().filter(document -> document.getStatus() == KycDocumentStatus.RESUBMISSION_REQUIRED).count();
        long rejected = documents.stream().filter(document -> document.getStatus() == KycDocumentStatus.REJECTED).count();
        long unreadable = documents.stream().filter(document -> document.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE).count();
        long expired = documents.stream().filter(document -> document.getValidityStatus() == KycDocumentValidityStatus.EXPIRED).count();
        long failed = documents.stream()
                .filter(document -> document.getStatus() == KycDocumentStatus.RESUBMISSION_REQUIRED
                        || document.getStatus() == KycDocumentStatus.REJECTED
                        || document.getValidityStatus() == KycDocumentValidityStatus.UNREADABLE
                        || document.getValidityStatus() == KycDocumentValidityStatus.EXPIRED)
                .count();
        return new KycOperationalMetricsResponse.DocumentPerformanceMetric(
                type.name(),
                total,
                approved,
                resubmission,
                rejected,
                unreadable,
                expired,
                rate(failed, total)
        );
    }

    private List<String> v2Priorities(Map<KycCaseStatus, Long> statusCounts,
                                      Map<String, Long> reasonCounts,
                                      List<KycDocumentEntity> documents,
                                      long authFailures) {
        Map<String, Long> signals = new LinkedHashMap<>();
        signals.put("Add production OCR and image quality scoring", reasonCounts.getOrDefault(KycReasonCode.DOCUMENT_UNREADABLE.name(), 0L));
        signals.put("Improve selfie capture UX and biometric engine", reasonCounts.getOrDefault(KycReasonCode.SELFIE_UNREADABLE.name(), 0L));
        signals.put("Improve biometric mismatch handling", reasonCounts.getOrDefault(KycReasonCode.BIOMETRIC_MISMATCH.name(), 0L));
        signals.put("Improve declared identity edit/reconciliation UX", reasonCounts.getOrDefault(KycReasonCode.DECLARED_INFO_MISMATCH.name(), 0L));
        signals.put("Tune review thresholds and add manual review tooling", statusCounts.getOrDefault(KycCaseStatus.REVIEW, 0L));
        signals.put("Improve supported document detection and country rules", reasonCounts.getOrDefault(KycReasonCode.UNSUPPORTED_DOCUMENT_TYPE.name(), 0L));
        signals.put("Harden API onboarding, credentials and rate-limit support", authFailures);
        documentPerformance(documents).stream()
                .max(Comparator.comparing(KycOperationalMetricsResponse.DocumentPerformanceMetric::failureRate))
                .filter(metric -> metric.total() > 0 && metric.failureRate() > 0)
                .ifPresent(metric -> signals.put("Prioritize document type improvements for " + metric.documentType(), metric.resubmissionRequired() + metric.rejected() + metric.unreadable() + metric.expired()));

        return signals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .limit(5)
                .toList();
    }

    private KycOperationalMetricsResponse.OutcomeMetric outcome(Map<KycCaseStatus, Long> counts,
                                                                KycCaseStatus status,
                                                                long total) {
        long count = counts.getOrDefault(status, 0L);
        return new KycOperationalMetricsResponse.OutcomeMetric(count, rate(count, total));
    }

    private List<KycOperationalMetricsResponse.CountMetric> topCounts(Map<String, Long> counts,
                                                                      long total,
                                                                      int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new KycOperationalMetricsResponse.CountMetric(entry.getKey(), entry.getValue(), rate(entry.getValue(), total)))
                .toList();
    }

    private Optional<KycDocumentEntity> latestIdentityDocument(List<KycDocumentEntity> documents) {
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }
        return documents.stream()
                .filter(document -> document.getDocumentType() != KycDocumentType.SELFIE
                        && document.getDocumentType() != KycDocumentType.FIELD_AGENT_PHOTO)
                .reduce((first, second) -> second);
    }

    private Optional<Duration> processingDuration(KycCaseEntity kycCase) {
        return processingMillis(kycCase).map(Duration::ofMillis);
    }

    private Optional<Long> processingMillis(KycCaseEntity kycCase) {
        if (kycCase.getCreatedAt() == null || kycCase.getLastProcessedAt() == null) {
            return Optional.empty();
        }
        long millis = Duration.between(kycCase.getCreatedAt(), kycCase.getLastProcessedAt()).toMillis();
        return millis < 0 ? Optional.empty() : Optional.of(millis);
    }

    private Map<String, Object> metadata(KycDocumentEntity document) {
        if (document.getMetadataJson() == null || document.getMetadataJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(document.getMetadataJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String productStatus(KycCaseStatus status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return status == KycCaseStatus.APPROVED ? "VERIFIED" : status.name();
    }

    private double rate(long count, long total) {
        if (total <= 0) {
            return 0D;
        }
        return round((count * 100D) / total);
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
