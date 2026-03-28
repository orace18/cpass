package com.cpass.platform.client.domain;

public enum ClientScope {
    SMS_SEND("sms:send"),
    WHATSAPP_SEND("whatsapp:send"),
    OTP_SEND("otp:send"),
    KYC_READ("kyc:read"),
    KYC_WRITE("kyc:write");

    private final String scope;

    ClientScope(String scope) {
        this.scope = scope;
    }

    public String scope() {
        return scope;
    }

    public String authority() {
        return "SCOPE_" + scope;
    }

    public static ClientScope fromScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Unknown scope: " + scope);
        }
        String normalized = scope.trim();
        for (ClientScope candidate : values()) {
            if (candidate.scope.equalsIgnoreCase(normalized) || candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown scope: " + scope);
    }
}
