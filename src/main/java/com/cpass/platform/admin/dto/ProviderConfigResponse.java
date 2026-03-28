package com.cpass.platform.admin.dto;

import java.util.Map;

public record ProviderConfigResponse(
        String id,
        String provider,
        String channel,
        boolean enabled,
        int priority,
        boolean configured,
        Map<String, Object> config
) {
}
