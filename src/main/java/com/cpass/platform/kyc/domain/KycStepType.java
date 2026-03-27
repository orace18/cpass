package com.cpass.platform.kyc.domain;

public enum KycStepType {
    OTP,
    ID_UPLOAD,
    OCR_EXTRACTION,
    DOCUMENT_EXPIRY_CHECK,
    FACE_MATCH,
    FIELD_VERIFICATION,
    SOCIAL_GUARANTEE,
    EXTERNAL_PROVIDER_FALLBACK
}
