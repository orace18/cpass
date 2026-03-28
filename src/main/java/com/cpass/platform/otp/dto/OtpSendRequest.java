package com.cpass.platform.otp.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record OtpSendRequest(@NotBlank @JsonAlias({"to", "phoneNumber"}) String destination) {
}
