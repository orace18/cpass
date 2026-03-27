package com.cpass.platform.admin.dto;

public record AdminLoginResponse(
        String token,
        AdminUserDto user
) {
    public record AdminUserDto(String id, String name, String email, String role) {}
}
