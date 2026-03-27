package com.cpass.platform.kyc.domain;

public enum KycLevel {
    LEVEL_0,
    LEVEL_1,
    LEVEL_2,
    LEVEL_3;

    public boolean satisfies(KycLevel requiredLevel) {
        return this.ordinal() >= requiredLevel.ordinal();
    }

    public static KycLevel max(KycLevel first, KycLevel second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
