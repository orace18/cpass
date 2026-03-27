package com.cpass.platform.common.security;

import com.cpass.platform.client.domain.ClientScope;
import com.cpass.platform.kyc.domain.KycLevel;

import java.util.Set;
import java.util.UUID;

public record ClientPrincipal(
        UUID clientId,
        String clientName,
        String clientPublicId,
        Set<ClientScope> scopes,
        KycLevel kycLevelRequired
) {
}
