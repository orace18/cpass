package com.cpass.platform.messaging.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SendSmsRequest(
        @NotBlank String recipient,
        String rawMessage,
        String templateCode,
        Map<String, String> templateParams
) {
}
