package com.cpass.platform.admin.dto;

/**
 * Response for GET/POST /admin/providers/routemobile/config.
 * Password is never returned; {@code hasPassword} indicates whether one is stored.
 */
public record RouteMobileConfigResponse(
        String  providerId,
        String  code,
        String  channel,
        boolean active,
        String  username,
        boolean hasPassword,
        String  senderId,
        double  unitCost,
        boolean debug
) {}
