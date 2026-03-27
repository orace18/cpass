package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycLevel;
import com.cpass.platform.kyc.domain.KycStepType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
public class KycFlowProperties {

    private final String level0;
    private final String level1;
    private final String level2;
    private final String level3;
    private final int fallbackScoreThreshold;
    private final int autoApproveScoreThreshold;
    private final long validityDays;
    private final boolean asyncEnabled;

    public KycFlowProperties(@Value("${app.kyc.flow.level-0:OTP}") String level0,
                             @Value("${app.kyc.flow.level-1:OTP,ID_UPLOAD,OCR_EXTRACTION,DOCUMENT_EXPIRY_CHECK,FACE_MATCH}") String level1,
                             @Value("${app.kyc.flow.level-2:OTP,ID_UPLOAD,OCR_EXTRACTION,DOCUMENT_EXPIRY_CHECK,FACE_MATCH,FIELD_VERIFICATION}") String level2,
                             @Value("${app.kyc.flow.level-3:OTP,ID_UPLOAD,OCR_EXTRACTION,DOCUMENT_EXPIRY_CHECK,FACE_MATCH,FIELD_VERIFICATION,SOCIAL_GUARANTEE,EXTERNAL_PROVIDER_FALLBACK}") String level3,
                             @Value("${app.kyc.external-fallback-score-threshold:70}") int fallbackScoreThreshold,
                             @Value("${app.kyc.auto-approve-score-threshold:75}") int autoApproveScoreThreshold,
                             @Value("${app.kyc.validity-days:365}") long validityDays,
                             @Value("${app.kyc.async-enabled:true}") boolean asyncEnabled) {
        this.level0 = level0;
        this.level1 = level1;
        this.level2 = level2;
        this.level3 = level3;
        this.fallbackScoreThreshold = fallbackScoreThreshold;
        this.autoApproveScoreThreshold = autoApproveScoreThreshold;
        this.validityDays = validityDays;
        this.asyncEnabled = asyncEnabled;
    }

    public List<KycStepType> stepsFor(KycLevel level) {
        return Arrays.stream(rawFlow(level).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(KycStepType::valueOf)
                .toList();
    }

    public int fallbackScoreThreshold() {
        return fallbackScoreThreshold;
    }

    public int autoApproveScoreThreshold() {
        return autoApproveScoreThreshold;
    }

    public Duration validityPeriod() {
        return Duration.ofDays(validityDays);
    }

    public boolean asyncEnabled() {
        return asyncEnabled;
    }

    private String rawFlow(KycLevel level) {
        return switch (level) {
            case LEVEL_0 -> level0;
            case LEVEL_1 -> level1;
            case LEVEL_2 -> level2;
            case LEVEL_3 -> level3;
        };
    }
}
