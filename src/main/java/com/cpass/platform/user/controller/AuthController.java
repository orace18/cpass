package com.cpass.platform.user.controller;

import com.cpass.platform.user.dto.BasicResponse;
import com.cpass.platform.user.dto.ForgotPasswordRequest;
import com.cpass.platform.user.dto.ResetPasswordRequest;
import com.cpass.platform.user.dto.TwoFactorChallengeResponse;
import com.cpass.platform.user.dto.TwoFactorVerifyRequest;
import com.cpass.platform.user.dto.UserLoginRequest;
import com.cpass.platform.user.dto.UserTokenResponse;
import com.cpass.platform.user.service.UserAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserAuthService userAuthService;

    public AuthController(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    @PostMapping("/login")
    public TwoFactorChallengeResponse login(@Valid @RequestBody UserLoginRequest request) {
        return userAuthService.login(request);
    }

    @PostMapping("/2fa/verify")
    public UserTokenResponse verifyTwoFactor(@Valid @RequestBody TwoFactorVerifyRequest request) {
        return userAuthService.verifyTwoFactor(request);
    }

    @PostMapping("/forgot-password")
    public BasicResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return userAuthService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    public BasicResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return userAuthService.resetPassword(request);
    }
}
