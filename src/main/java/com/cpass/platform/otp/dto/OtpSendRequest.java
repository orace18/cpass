package com.cpass.platform.otp.dto;

import com.cpass.platform.messaging.domain.MessageChannel;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record OtpSendRequest(
        @NotBlank @JsonAlias({"to", "phoneNumber"}) String destination,
        @JsonAlias("channel") MessageChannel channel
) {
    public OtpSendRequest {
        if (channel == null) channel = MessageChannel.SMS;
    }
}
