package com.cpass.platform.otp.controller;

import com.cpass.platform.common.security.ClientContextHolder;
import com.cpass.platform.otp.dto.OtpSendRequest;
import com.cpass.platform.otp.dto.OtpSendResponse;
import com.cpass.platform.otp.dto.OtpVerifyRequest;
import com.cpass.platform.otp.dto.OtpVerifyResponse;
import com.cpass.platform.otp.service.OtpService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/otp")
public class OtpController {

    private final OtpService otpService;

    public OtpController(OtpService otpService) {
        this.otpService = otpService;
    }

    @PostMapping("/send")
    public OtpSendResponse send(@Valid @RequestBody OtpSendRequest request) {
        UUID clientId = ClientContextHolder.requireClientId();
        return otpService.sendOtp(clientId, request.destination(), request.channel());
    }

    @PostMapping("/verify")
    public OtpVerifyResponse verify(@Valid @RequestBody OtpVerifyRequest request) {
        UUID clientId = ClientContextHolder.requireClientId();
        return otpService.verifyOtp(clientId, request.referenceId(), request.otpCode());
    }
}
