package com.cpass.platform.provider.domain;

import com.cpass.platform.messaging.domain.MessageChannel;

import java.util.Map;
import java.util.UUID;

public record ProviderMessage(
        UUID clientId,
        MessageChannel channel,
        String recipient,
        String rawMessage,
        String templateCode,
        Map<String, String> templateParams
) {
}
