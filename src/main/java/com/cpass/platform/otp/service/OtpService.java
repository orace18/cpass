package com.cpass.platform.otp.service;

import com.cpass.platform.billing.service.QuotaService;
import com.cpass.platform.billing.service.UsageService;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.otp.domain.OtpAuditEntity;
import com.cpass.platform.otp.domain.OtpStatus;
import com.cpass.platform.otp.dto.OtpSendResponse;
import com.cpass.platform.otp.dto.OtpVerifyResponse;
import com.cpass.platform.otp.infra.OtpAuditRepository;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.cpass.platform.provider.service.ProviderDispatchResult;
import com.cpass.platform.provider.service.ProviderRoutingService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OtpService {

    private final OtpAuditRepository otpAuditRepository;
    private final ProviderRoutingService providerRoutingService;
    private final QuotaService quotaService;
    private final UsageService usageService;
    private final OtpProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(OtpAuditRepository otpAuditRepository,
                      ProviderRoutingService providerRoutingService,
                      QuotaService quotaService,
                      UsageService usageService,
                      OtpProperties properties) {
        this.otpAuditRepository = otpAuditRepository;
        this.providerRoutingService = providerRoutingService;
        this.quotaService = quotaService;
        this.usageService = usageService;
        this.properties = properties;
    }

    @Transactional
    public OtpSendResponse sendOtp(UUID clientId, String destination) {
        quotaService.assertQuota(clientId, MessageChannel.SMS);
        assertSendAllowed(clientId, destination);

        String otp = generateOtp(properties.length());
        String salt = UUID.randomUUID().toString().replace("-", "");
        String hash = hashOtp(salt, otp);
        Instant expiresAt = Instant.now().plusSeconds(properties.ttlSeconds());
        UUID reference = UUID.randomUUID();

        OtpAuditEntity audit = new OtpAuditEntity();
        audit.setClientId(clientId);
        audit.setReferenceId(reference);
        audit.setDestination(destination);
        audit.setChannel(MessageChannel.SMS);
        audit.setOtpHash(hash);
        audit.setSalt(salt);
        audit.setStatus(OtpStatus.SENT);
        audit.setAttemptsRemaining(properties.maxVerifyAttempts());
        audit.setExpiresAt(expiresAt);
        otpAuditRepository.save(audit);

        ProviderDispatchResult dispatchResult = providerRoutingService.dispatch(new ProviderMessage(
                clientId,
                MessageChannel.SMS,
                destination,
                properties.messageTemplate().replace("{otp}", otp),
                null,
                Map.of()
        ));

        if (!dispatchResult.success()) {
            audit.setStatus(OtpStatus.FAILED);
            otpAuditRepository.save(audit);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OTP_PROVIDER_FAILED", dispatchResult.error());
        }

        usageService.recordUsage(clientId, MessageChannel.SMS, dispatchResult.cost() == null ? BigDecimal.ZERO : dispatchResult.cost());
        return new OtpSendResponse(reference, expiresAt);
    }

    @Transactional
    public OtpVerifyResponse verifyOtp(UUID clientId, UUID referenceId, String otpCode) {
        OtpAuditEntity audit = otpAuditRepository.findByReferenceIdAndClientId(referenceId, clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "OTP expired or invalid"));

        if (audit.getStatus() != OtpStatus.SENT || audit.getExpiresAt().isBefore(Instant.now())) {
            audit.setStatus(OtpStatus.EXPIRED);
            otpAuditRepository.save(audit);
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "OTP expired or already used");
        }

        if (audit.getOtpHash().equals(hashOtp(audit.getSalt(), otpCode))) {
            audit.setStatus(OtpStatus.VERIFIED);
            audit.setVerifiedAt(Instant.now());
            otpAuditRepository.save(audit);
            return new OtpVerifyResponse(true, audit.getAttemptsRemaining());
        }

        int remaining = audit.getAttemptsRemaining() - 1;
        audit.setAttemptsRemaining(remaining);
        if (remaining <= 0) {
            audit.setStatus(OtpStatus.FAILED);
            otpAuditRepository.save(audit);
            return new OtpVerifyResponse(false, 0);
        }

        otpAuditRepository.save(audit);
        return new OtpVerifyResponse(false, remaining);
    }

    private void assertSendAllowed(UUID clientId, String destination) {
        Instant cooldownThreshold = Instant.now().minusSeconds(properties.cooldownSeconds());
        List<OtpAuditEntity> recent = otpAuditRepository
                .findByClientIdAndDestinationAndStatusAndCreatedAtAfter(clientId, destination, OtpStatus.SENT, cooldownThreshold);
        if (!recent.isEmpty()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_COOLDOWN", "Please wait before requesting another OTP");
        }

        Instant retryThreshold = Instant.now().minusSeconds(properties.ttlSeconds());
        long count = otpAuditRepository.countByClientIdAndDestinationAndCreatedAtAfter(clientId, destination, retryThreshold);
        if (count >= properties.maxSendRetries()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RETRY_LIMIT", "Max OTP retries exceeded");
        }
    }

    private String hashOtp(String salt, String otpCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = (salt + ":" + otpCode + ":" + properties.hashPepper()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(raw));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OTP_HASH_FAILED", "Failed to hash OTP");
        }
    }

    private String generateOtp(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(secureRandom.nextInt(10));
        }
        return builder.toString();
    }
}
