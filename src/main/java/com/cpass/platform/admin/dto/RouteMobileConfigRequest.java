package com.cpass.platform.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request body for POST /admin/providers/routemobile/config.
 * {@code password} is optional — when null/blank the existing encrypted password is kept.
 */
public record RouteMobileConfigRequest(
        @NotBlank String username,
        String   password,   // null or blank = keep existing encrypted value
        String   senderId,
        @Positive Double unitCost,
        Boolean  debug
) {}
