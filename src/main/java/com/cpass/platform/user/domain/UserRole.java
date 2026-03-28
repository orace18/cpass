package com.cpass.platform.user.domain;

public enum UserRole {
    ADMIN,
    CLIENT;

    public String authority() {
        return "ROLE_" + name();
    }
}
