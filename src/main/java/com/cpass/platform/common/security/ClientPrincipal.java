package com.cpass.platform.common.security;

import java.util.UUID;

public record ClientPrincipal(UUID clientId, String clientName, String apiKey) {
}
