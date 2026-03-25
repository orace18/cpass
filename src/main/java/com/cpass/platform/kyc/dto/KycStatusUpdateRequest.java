package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycStatus;
import jakarta.validation.constraints.NotNull;

public record KycStatusUpdateRequest(@NotNull KycStatus status, String reason) {
}
