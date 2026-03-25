package com.cpass.platform.kyc.dto;

import com.cpass.platform.kyc.domain.KycStatus;

import java.util.UUID;

public record KycUploadResponse(UUID documentId, KycStatus status, String objectKey) {
}
