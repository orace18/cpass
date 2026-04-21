package com.cpass.platform.common.security;

import java.util.UUID;

public final class RequestCorrelation {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestCorrelation() {
    }

    public static String setOrGenerate(String requestId) {
        String resolved = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString()
                : requestId.trim();
        REQUEST_ID.set(resolved);
        return resolved;
    }

    public static String requestId() {
        return REQUEST_ID.get();
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}
