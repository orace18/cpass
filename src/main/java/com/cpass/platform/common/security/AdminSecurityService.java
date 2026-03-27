package com.cpass.platform.common.security;

import com.cpass.platform.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminSecurityService {

    private final String adminToken;

    public AdminSecurityService(@Value("${app.admin-token}") String adminToken) {
        this.adminToken = adminToken;
    }

    public void ensureAdminToken(String headerToken) {
        if (!isAdminToken(headerToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Invalid admin token");
        }
    }

    public boolean isAdminToken(String headerToken) {
        return headerToken != null && headerToken.equals(adminToken);
    }

    public String adminToken() {
        return adminToken;
    }
}
