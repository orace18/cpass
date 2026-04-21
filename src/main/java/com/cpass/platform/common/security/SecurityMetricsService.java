package com.cpass.platform.common.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SecurityMetricsService {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, AtomicLong> authFailuresByCode = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> authFailuresByMode = new ConcurrentHashMap<>();

    public SecurityMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordAuthFailure(String mode, String code, String path) {
        String safeMode = safeTag(mode);
        String safeCode = safeTag(code);
        authFailuresByCode.computeIfAbsent(safeCode, ignored -> new AtomicLong()).incrementAndGet();
        authFailuresByMode.computeIfAbsent(safeMode, ignored -> new AtomicLong()).incrementAndGet();
        meterRegistry.counter(
                "cpass.auth.failures",
                "mode", safeMode,
                "code", safeCode,
                "path", safePath(path)
        ).increment();
    }

    public AuthFailureSnapshot authFailureSnapshot() {
        Map<String, Long> byCode = toSortedMap(authFailuresByCode);
        Map<String, Long> byMode = toSortedMap(authFailuresByMode);
        long total = byCode.values().stream().mapToLong(Long::longValue).sum();
        return new AuthFailureSnapshot(total, byCode, byMode);
    }

    private Map<String, Long> toSortedMap(ConcurrentMap<String, AtomicLong> counters) {
        Map<String, Long> values = new LinkedHashMap<>();
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.put(entry.getKey(), entry.getValue().get()));
        return values;
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }

    private String safePath(String path) {
        if (path == null || path.isBlank()) {
            return "UNKNOWN";
        }
        if (path.startsWith("/kyc")) {
            return "/kyc";
        }
        if (path.startsWith("/admin")) {
            return "/admin";
        }
        if (path.startsWith("/auth")) {
            return "/auth";
        }
        return "OTHER";
    }

    public record AuthFailureSnapshot(
            long total,
            Map<String, Long> byCode,
            Map<String, Long> byMode
    ) {
    }
}
