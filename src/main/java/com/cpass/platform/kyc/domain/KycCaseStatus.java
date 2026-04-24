package com.cpass.platform.kyc.domain;

public enum KycCaseStatus {
    INITIATED,
    CONTACT_VERIFIED,
    IDENTITY_PRECHECKED,
    FIELD_VERIFIED,
    SOCIAL_VALIDATED,
    APPROVED,
    REJECTED,
    REVIEW,
    RESUBMISSION_REQUIRED,
    ARCHIVED
}
