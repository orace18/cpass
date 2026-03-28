package com.cpass.platform.email.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.email.bootstrap")
public record EmailBootstrapProperties(
        boolean enabled,
        String host,
        Integer port,
        String username,
        String password,
        String fromEmail,
        String fromName
) {

    public boolean isConfigured() {
        return enabled
                && host != null && !host.isBlank()
                && port != null
                && fromEmail != null && !fromEmail.isBlank();
    }
}
