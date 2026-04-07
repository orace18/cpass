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
import com.cpass.platform.webhook.service.ClientWebhookEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OtpService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OtpAuditRepository otpAuditRepository;
    private final ProviderRoutingService providerRoutingService;
    private final QuotaService quotaService;
    private final UsageService usageService;
    private final OtpProperties properties;
    private final ClientWebhookEventService clientWebhookEventService;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(StringRedisTemplate redisTemplate,
                      ObjectMapper objectMapper,
                      OtpAuditRepository otpAuditRepository,
                      ProviderRoutingService providerRoutingService,
                      QuotaService quotaService,
                      UsageService usageService,
                      OtpProperties properties,
                      ClientWebhookEventService clientWebhookEventService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.otpAuditRepository = otpAuditRepository;
        this.providerRoutingService = providerRoutingService;
        this.quotaService = quotaService;
        this.usageService = usageService;
        this.properties = properties;
        this.clientWebhookEventService = clientWebhookEventService;
    }

    @Transactional
    public OtpSendResponse sendOtp(UUID clientId, String destination, MessageChannel channel) {
        quotaService.assertQuota(clientId, channel);
        assertSendAllowed(clientId, destination);

        String otp = generateOtp(properties.length());
        String salt = UUID.randomUUID().toString().replace("-", "");
        String hash = hashOtp(salt, otp);
        Instant expiresAt = Instant.now().plusSeconds(properties.ttlSeconds());
        UUID reference = UUID.randomUUID();

        OtpCacheRecord record = new OtpCacheRecord(
                destination,
                channel,
                hash,
                salt,
                properties.maxVerifyAttempts(),
                expiresAt
        );
        saveCache(clientId, reference, record);

        OtpAuditEntity audit = new OtpAuditEntity();
        audit.setClientId(clientId);
        audit.setReferenceId(reference);
        audit.setDestination(destination);
        audit.setChannel(channel);
        audit.setOtpHash(hash);
        audit.setSalt(salt);
        audit.setStatus(OtpStatus.SENT);
        audit.setAttemptsRemaining(properties.maxVerifyAttempts());
        audit.setExpiresAt(expiresAt);
        otpAuditRepository.save(audit);

        ProviderDispatchResult dispatchResult = providerRoutingService.dispatch(new ProviderMessage(
                clientId,
                channel,
                destination,
                properties.messageTemplate().replace("{otp}", otp),
                null,
                Map.of()
        ));

        if (!dispatchResult.success()) {
            audit.setProviderCode(dispatchResult.providerCode());
            audit.setStatus(OtpStatus.FAILED);
            otpAuditRepository.save(audit);
            redisTemplate.delete(otpKey(clientId, reference));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OTP_PROVIDER_FAILED", dispatchResult.error());
        }

        audit.setProviderCode(dispatchResult.providerCode());
        otpAuditRepository.save(audit);
        usageService.recordUsage(clientId, channel, dispatchResult.cost() == null ? BigDecimal.ZERO : dispatchResult.cost());
        clientWebhookEventService.enqueueOtpEvent(audit, "otp.sent");
        return new OtpSendResponse(reference, expiresAt);
    }

    @Transactional
    public OtpVerifyResponse verifyOtp(UUID clientId, UUID referenceId, String otpCode) {
        Optional<OtpCacheRecord> recordOptional = loadCache(clientId, referenceId);
        if (recordOptional.isEmpty()) {
            updateAudit(clientId, referenceId, OtpStatus.EXPIRED, 0)
                    .ifPresent(audit -> clientWebhookEventService.enqueueOtpEvent(audit, "otp.expired"));
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "OTP expired or invalid");
        }

        OtpCacheRecord record = recordOptional.get();
        if (record.expiresAt().isBefore(Instant.now())) {
            redisTemplate.delete(otpKey(clientId, referenceId));
            updateAudit(clientId, referenceId, OtpStatus.EXPIRED, 0)
                    .ifPresent(audit -> clientWebhookEventService.enqueueOtpEvent(audit, "otp.expired"));
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "OTP expired");
        }

        if (record.hash().equals(hashOtp(record.salt(), otpCode))) {
            redisTemplate.delete(otpKey(clientId, referenceId));
            updateAudit(clientId, referenceId, OtpStatus.VERIFIED, record.attemptsRemaining())
                    .ifPresent(audit -> clientWebhookEventService.enqueueOtpEvent(audit, "otp.verified"));
            return new OtpVerifyResponse(true, record.attemptsRemaining());
        }

        int remaining = record.attemptsRemaining() - 1;
        if (remaining <= 0) {
            redisTemplate.delete(otpKey(clientId, referenceId));
            updateAudit(clientId, referenceId, OtpStatus.FAILED, 0);
            return new OtpVerifyResponse(false, 0);
        }

        saveCache(clientId, referenceId, new OtpCacheRecord(
                record.destination(),
                record.channel(),
                record.hash(),
                record.salt(),
                remaining,
                record.expiresAt()
        ));
        updateAudit(clientId, referenceId, OtpStatus.SENT, remaining);
        return new OtpVerifyResponse(false, remaining);
    }

    private void assertSendAllowed(UUID clientId, String destination) {
        String cooldownKey = cooldownKey(clientId, destination);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_COOLDOWN", "Please wait before requesting another OTP");
        }

        String retryKey = retryKey(clientId, destination);
        Long count = redisTemplate.opsForValue().increment(retryKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(retryKey, Duration.ofSeconds(properties.ttlSeconds()));
        }
        if (count != null && count > properties.maxSendRetries()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RETRY_LIMIT", "Max OTP retries exceeded");
        }

        redisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(properties.cooldownSeconds()));
    }

    private void saveCache(UUID clientId, UUID referenceId, OtpCacheRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(
                    otpKey(clientId, referenceId),
                    json,
                    Duration.ofSeconds(properties.ttlSeconds())
            );
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OTP_STORE_FAILED", "Failed to store OTP");
        }
    }

    private Optional<OtpCacheRecord> loadCache(UUID clientId, UUID referenceId) {
        try {
            String value = redisTemplate.opsForValue().get(otpKey(clientId, referenceId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, OtpCacheRecord.class));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<OtpAuditEntity> updateAudit(UUID clientId, UUID referenceId, OtpStatus status, int attemptsRemaining) {
        return otpAuditRepository.findByReferenceIdAndClientId(referenceId, clientId)
                .map(audit -> {
                    if (audit.getStatus() == OtpStatus.VERIFIED && status != OtpStatus.VERIFIED) {
                        return null;
                    }

                    boolean changed = audit.getStatus() != status || audit.getAttemptsRemaining() != attemptsRemaining;
                    if (!changed) {
                        return null;
                    }

                    audit.setStatus(status);
                    audit.setAttemptsRemaining(attemptsRemaining);
                    if (status == OtpStatus.VERIFIED && audit.getVerifiedAt() == null) {
                        audit.setVerifiedAt(Instant.now());
                    }
                    return otpAuditRepository.save(audit);
                });
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

    private String otpKey(UUID clientId, UUID referenceId) {
        return "otp:data:" + clientId + ":" + referenceId;
    }

    private String cooldownKey(UUID clientId, String destination) {
        return "otp:cooldown:" + clientId + ":" + destination;
    }

    private String retryKey(UUID clientId, String destination) {
        return "otp:retry:" + clientId + ":" + destination;
    }
}
