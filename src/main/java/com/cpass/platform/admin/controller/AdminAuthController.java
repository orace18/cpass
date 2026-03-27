package com.cpass.platform.admin.controller;

import com.cpass.platform.admin.dto.AdminLoginRequest;
import com.cpass.platform.admin.dto.AdminLoginResponse;
import com.cpass.platform.common.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin authentication — validates static credentials from config.
 * Returns the admin token so the front-end can use it for X-ADMIN-TOKEN headers.
 */
@RestController
@RequestMapping("/auth")
public class AdminAuthController {

    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;
    private final String adminToken;

    public AdminAuthController(
            @Value("${app.admin.email}")    String adminEmail,
            @Value("${app.admin.password}") String adminPassword,
            @Value("${app.admin.name}")     String adminName,
            @Value("${app.admin-token}")    String adminToken) {
        this.adminEmail    = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName     = adminName;
        this.adminToken    = adminToken;
    }

    @PostMapping("/login")
    public AdminLoginResponse login(@Valid @RequestBody AdminLoginRequest request) {
        if (!adminEmail.equalsIgnoreCase(request.email()) ||
                !adminPassword.equals(request.password())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS", "Invalid email or password");
        }

        AdminLoginResponse.AdminUserDto user = new AdminLoginResponse.AdminUserDto(
                "admin-1", adminName, adminEmail, "ADMIN"
        );
        // Token = adminToken so the front-end can use it as X-ADMIN-TOKEN automatically
        return new AdminLoginResponse(adminToken, user);
    }
}
