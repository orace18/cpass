package com.cpass.platform.kyc.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record KycOperationalMetricsResponse(
        Instant generatedAt,
        long totalCases,
        OutcomeMetric verified,
        OutcomeMetric resubmission,
        OutcomeMetric review,
        OutcomeMetric rejected,
        Map<String, Long> caseStatusCounts,
        Map<String, Double> caseStatusRates,
        List<CountMetric> topReasonCodes,
        double avgProcessingTimeMillis,
        AuthFailureMetric authFailures,
        FrictionMetric friction,
        List<String> dataDrivenV2Priorities
) {

    public record OutcomeMetric(
            long count,
            double rate
    ) {
    }

    public record CountMetric(
            String key,
            long count,
            double rate
    ) {
    }

    public record AuthFailureMetric(
            long total,
            Map<String, Long> byCode,
            Map<String, Long> byMode
    ) {
    }

    public record FrictionMetric(
            List<CountMetric> topUnreadableCauses,
            Map<String, Long> selfieFailureCauses,
            long declaredInfoMismatchCount,
            List<DocumentPerformanceMetric> leastPerformingDocumentTypes
    ) {
    }

    public record DocumentPerformanceMetric(
            String documentType,
            long total,
            long approved,
            long resubmissionRequired,
            long rejected,
            long unreadable,
            long expired,
            double failureRate
    ) {
    }
}
