package com.cpass.platform.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.hmac")
public record HmacSecurityProperties(
        long allowedSkewSeconds,
        long nonceTtlSeconds,
        String signingVersion
) {
}
