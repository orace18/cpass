package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
public class ConfigDrivenExternalKycProvider implements KycProviderAdapter {

    private final ObjectMapper objectMapper;

    public ConfigDrivenExternalKycProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return "EXTERNAL_KYC_FALLBACK";
    }

    @Override
    public KycProviderDecision review(KycCaseEntity kycCase, int currentScore, ProviderConfigEntity config) {
        Map<String, Object> cfg = parse(config.getConfigJson());
        int approvalThreshold = asInt(cfg.get("approvalThreshold"), 65);
        int reviewThreshold = asInt(cfg.get("reviewThreshold"), 45);
        if (currentScore >= approvalThreshold) {
            return new KycProviderDecision(KycCaseStatus.APPROVED, Math.min(95, currentScore + 15), "Approved by external fallback provider");
        }
        if (currentScore >= reviewThreshold) {
            return new KycProviderDecision(KycCaseStatus.REVIEW, currentScore + 10, "External fallback provider requested manual review");
        }
        return new KycProviderDecision(KycCaseStatus.REJECTED, currentScore, "External fallback provider rejected the case");
    }

    private Map<String, Object> parse(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Collections.emptyMap();
        }
    }

    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
