package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycCaseStatus;

public record KycProviderDecision(KycCaseStatus status, int score, String reason) {
}
