package com.cpass.platform.user.service;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.UserJwtService;
import com.cpass.platform.common.security.UserPrincipal;
import com.cpass.platform.email.service.EmailService;
import com.cpass.platform.user.domain.PasswordResetPurpose;
import com.cpass.platform.user.domain.UserEntity;
import com.cpass.platform.user.domain.UserTwoFactorChallengeEntity;
import com.cpass.platform.user.dto.BasicResponse;
import com.cpass.platform.user.dto.ChangePasswordRequest;
import com.cpass.platform.user.dto.ForgotPasswordRequest;
import com.cpass.platform.user.dto.ResetPasswordRequest;
import com.cpass.platform.user.dto.TwoFactorChallengeResponse;
import com.cpass.platform.user.dto.TwoFactorVerifyRequest;
import com.cpass.platform.user.dto.UserLoginRequest;
import com.cpass.platform.user.dto.UserTokenResponse;
import com.cpass.platform.user.infra.UserRepository;
import com.cpass.platform.user.infra.UserTwoFactorChallengeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Service
public class UserAuthService {

    private final UserRepository userRepository;
    private final UserTwoFactorChallengeRepository userTwoFactorChallengeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final UserJwtService userJwtService;
    private final UserService userService;
    private final int maxAttempts;
    private final long ttlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserAuthService(UserRepository userRepository,
                           UserTwoFactorChallengeRepository userTwoFactorChallengeRepository,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService,
                           UserJwtService userJwtService,
                           UserService userService,
                           @Value("${app.security.two-factor.max-attempts:5}") int maxAttempts,
                           @Value("${app.security.two-factor.ttl-seconds:300}") long ttlSeconds) {
        this.userRepository = userRepository;
        this.userTwoFactorChallengeRepository = userTwoFactorChallengeRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.userJwtService = userJwtService;
        this.userService = userService;
        this.maxAttempts = maxAttempts;
        this.ttlSeconds = ttlSeconds;
    }

    @Transactional
    public TwoFactorChallengeResponse login(UserLoginRequest request) {
        UserEntity user = userService.requireByEmail(request.email());
        if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        Instant now = Instant.now();
        userTwoFactorChallengeRepository.findByUserIdAndConsumedAtIsNull(user.getId())
                .forEach(challenge -> challenge.setConsumedAt(now));
        userTwoFactorChallengeRepository.deleteByExpiresAtBefore(now.minusSeconds(1));

        String code = generateCode();
        UserTwoFactorChallengeEntity challenge = new UserTwoFactorChallengeEntity();
        challenge.setUserId(user.getId());
        challenge.setCodeHash(passwordEncoder.encode(code));
        challenge.setAttemptsRemaining(maxAttempts);
        challenge.setExpiresAt(now.plusSeconds(ttlSeconds));
        UserTwoFactorChallengeEntity saved = userTwoFactorChallengeRepository.save(challenge);

        String twoFactorEmail = userService.resolveTwoFactorEmail(user);
        emailService.sendOtp(user.getClientId(), twoFactorEmail, code);
        return new TwoFactorChallengeResponse(
                saved.getId().toString(),
                ttlSeconds,
                maskEmail(twoFactorEmail),
                "A verification code has been sent to your email."
        );
    }

    @Transactional
    public UserTokenResponse verifyTwoFactor(TwoFactorVerifyRequest request) {
        UUID challengeId = parseUuid(request.challengeId());
        UserTwoFactorChallengeEntity challenge = userTwoFactorChallengeRepository.findByIdAndConsumedAtIsNull(challengeId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_2FA_CHALLENGE", "2FA challenge is invalid"));

        if (challenge.getExpiresAt().isBefore(Instant.now())) {
            challenge.setConsumedAt(Instant.now());
            userTwoFactorChallengeRepository.save(challenge);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_2FA_CHALLENGE", "2FA challenge has expired");
        }

        if (!passwordEncoder.matches(request.code(), challenge.getCodeHash())) {
            int remaining = Math.max(0, challenge.getAttemptsRemaining() - 1);
            challenge.setAttemptsRemaining(remaining);
            if (remaining == 0) {
                challenge.setConsumedAt(Instant.now());
            }
            userTwoFactorChallengeRepository.save(challenge);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_2FA_CODE", "Verification code is invalid");
        }

        challenge.setVerifiedAt(Instant.now());
        challenge.setConsumedAt(Instant.now());
        userTwoFactorChallengeRepository.save(challenge);

        UserEntity user = userService.requireUser(challenge.getUserId());
        UserJwtService.TokenIssue token = userJwtService.issueToken(user);
        return new UserTokenResponse(token.token(), token.expiresIn(), userService.toResponse(user));
    }

    @Transactional
    public BasicResponse forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.email().trim().toLowerCase())
                .filter(UserEntity::isEnabled)
                .ifPresent(user -> userService.inviteUser(user, PasswordResetPurpose.RESET));
        return new BasicResponse("ACCEPTED", "If the account exists, a reset email has been sent.");
    }

    @Transactional
    public BasicResponse resetPassword(ResetPasswordRequest request) {
        UserEntity user = userService.consumePasswordToken(request.token());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        return new BasicResponse("OK", "Password updated successfully.");
    }

    @Transactional
    public BasicResponse changePassword(UserPrincipal principal, ChangePasswordRequest request) {
        UserEntity user = userService.requireUser(principal.userId());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "Current password is invalid");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        return new BasicResponse("OK", "Password updated successfully.");
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_2FA_CHALLENGE", "2FA challenge is invalid");
        }
    }

    private String generateCode() {
        StringBuilder builder = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            builder.append(secureRandom.nextInt(10));
        }
        return builder.toString();
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(Math.max(atIndex, 0));
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
