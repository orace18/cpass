package com.cpass.platform.common.security;

import com.cpass.platform.client.domain.ApiKeyEntity;
import com.cpass.platform.client.infra.ApiKeyRepository;
import com.cpass.platform.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_API_KEY = "X-API-KEY";
    private static final String HEADER_TIMESTAMP = "X-TIMESTAMP";
    private static final String HEADER_NONCE = "X-NONCE";
    private static final String HEADER_SIGNATURE = "X-SIGNATURE";

    private final ApiKeyRepository apiKeyRepository;
    private final SecretCryptoService secretCryptoService;
    private final NonceStoreService nonceStoreService;
    private final ClientRateLimiter rateLimiter;
    private final HmacSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ApiAuthenticationFilter(ApiKeyRepository apiKeyRepository,
                                   SecretCryptoService secretCryptoService,
                                   NonceStoreService nonceStoreService,
                                   ClientRateLimiter rateLimiter,
                                   HmacSecurityProperties properties,
                                   ObjectMapper objectMapper) {
        this.apiKeyRepository = apiKeyRepository;
        this.secretCryptoService = secretCryptoService;
        this.nonceStoreService = nonceStoreService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/clients")
                || path.startsWith("/admin")
                || path.startsWith("/webhooks/delivery");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] bodyBytes = CachedBodyHttpServletRequest.readAllBytes(request);
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, bodyBytes);
        try {
            String apiKeyHeader = request.getHeader(HEADER_API_KEY);
            String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
            String nonceHeader = request.getHeader(HEADER_NONCE);
            String signatureHeader = request.getHeader(HEADER_SIGNATURE);

            if (isBlank(apiKeyHeader) || isBlank(timestampHeader) || isBlank(nonceHeader) || isBlank(signatureHeader)) {
                reject(response, HttpStatus.UNAUTHORIZED, "AUTH_HEADERS_REQUIRED", "Missing authentication headers");
                return;
            }

            long timestamp;
            try {
                timestamp = Long.parseLong(timestampHeader);
            } catch (NumberFormatException exception) {
                reject(response, HttpStatus.UNAUTHORIZED, "INVALID_TIMESTAMP", "Timestamp must be epoch seconds");
                return;
            }

            if (!isTimestampWithinSkew(timestamp)) {
                reject(response, HttpStatus.UNAUTHORIZED, "TIMESTAMP_EXPIRED", "Timestamp outside allowed skew");
                return;
            }

            Optional<ApiKeyEntity> apiKeyOptional = apiKeyRepository.findByApiKeyAndActiveTrue(apiKeyHeader);
            if (apiKeyOptional.isEmpty()) {
                reject(response, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "API key not found");
                return;
            }

            ApiKeyEntity apiKey = apiKeyOptional.get();
            String clientSecret = secretCryptoService.decrypt(apiKey.getSecretEncrypted());

            String payload = buildSigningPayload(request, timestampHeader, nonceHeader, bodyBytes);
            String expected = HmacSigner.hmacSha256Hex(clientSecret, payload);
            if (!HmacSigner.constantTimeEquals(expected, signatureHeader)) {
                reject(response, HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Signature mismatch");
                return;
            }

            if (!nonceStoreService.registerNonce(apiKeyHeader, nonceHeader, properties.nonceTtlSeconds())) {
                reject(response, HttpStatus.UNAUTHORIZED, "NONCE_REPLAYED", "Nonce already used");
                return;
            }

            if (!rateLimiter.isAllowed(apiKey.getClient().getId(), apiKey.getClient().getRateLimitPerMinute())) {
                reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded");
                return;
            }

            ClientContextHolder.set(new ClientPrincipal(
                    apiKey.getClient().getId(),
                    apiKey.getClient().getName(),
                    apiKeyHeader
            ));
            apiKey.markUsedNow();
            apiKeyRepository.save(apiKey);

            try {
                filterChain.doFilter(wrappedRequest, response);
            } finally {
                ClientContextHolder.clear();
            }
        } catch (Exception exception) {
            reject(response, HttpStatus.UNAUTHORIZED, "AUTH_VALIDATION_FAILED", exception.getMessage());
        }
    }

    private String buildSigningPayload(HttpServletRequest request, String timestamp, String nonce, byte[] bodyBytes) {
        String bodyHash = HmacSigner.sha256Hex(bodyBytes);
        return request.getMethod()
                + "\n"
                + request.getRequestURI()
                + "\n"
                + timestamp
                + "\n"
                + nonce
                + "\n"
                + bodyHash;
    }

    private boolean isTimestampWithinSkew(long timestampSeconds) {
        long now = Instant.now().getEpochSecond();
        return Math.abs(now - timestampSeconds) <= properties.allowedSkewSeconds();
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse(
                Instant.now(),
                status.value(),
                code,
                message,
                null,
                null
        );
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
