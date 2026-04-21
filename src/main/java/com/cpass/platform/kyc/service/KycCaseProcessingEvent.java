package com.cpass.platform.kyc.service;

import java.util.UUID;

public record KycCaseProcessingEvent(UUID kycCaseId, String requestId) {
}
