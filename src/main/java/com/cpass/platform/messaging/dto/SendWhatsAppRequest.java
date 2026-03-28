package com.cpass.platform.messaging.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SendWhatsAppRequest(
        @NotBlank @JsonAlias("to") String recipient,
        @JsonAlias("message") String rawMessage,
        String templateCode,
        Map<String, String> templateParams
) {
}
