package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.provider.domain.ProviderConfigEntity;

public interface KycProviderAdapter {

    String providerCode();

    KycProviderDecision review(KycCaseEntity kycCase, int currentScore, ProviderConfigEntity config);
}
