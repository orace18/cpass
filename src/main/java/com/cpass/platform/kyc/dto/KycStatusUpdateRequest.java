package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycDocumentStatus;
import jakarta.validation.constraints.NotNull;

public record KycStatusUpdateRequest(@NotNull KycDocumentStatus status, String reason) {
}
