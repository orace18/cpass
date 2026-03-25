package com.cpass.platform.provider.service;

import java.math.BigDecimal;

public record ProviderDispatchResult(
        boolean success,
        String providerCode,
        String providerMessageId,
        BigDecimal cost,
        String error
) {
    public static ProviderDispatchResult success(String providerCode, String providerMessageId, BigDecimal cost) {
        return new ProviderDispatchResult(true, providerCode, providerMessageId, cost, null);
    }

    public static ProviderDispatchResult failure(String providerCode, String error) {
        return new ProviderDispatchResult(false, providerCode, null, BigDecimal.ZERO, error);
    }
}
