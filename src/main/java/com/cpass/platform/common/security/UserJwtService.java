package com.cpass.platform.common.security;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.user.domain.UserEntity;
import com.cpass.platform.user.domain.UserRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UserJwtService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final String issuer;
    private final long ttlSeconds;

    public UserJwtService(ObjectMapper objectMapper,
                          @Value("${app.security.user-jwt.secret:${app.security.jwt.secret}}") String secret,
                          @Value("${app.security.user-jwt.issuer:cpass-platform-users}") String issuer,
                          @Value("${app.security.user-jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
    }

    public TokenIssue issueToken(UserEntity user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);

        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", user.getId().toString());
        payload.put("email", user.getEmail());
        payload.put("role", user.getRole().name());
        payload.put("cid", user.getClientId() == null ? null : user.getClientId().toString());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());

        String token = encodeJson(header) + "." + encodeJson(payload);
        return new TokenIssue(token + "." + sign(token), expiresAt, ttlSeconds);
    }

    public VerifiedToken verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Token format is invalid");
        }

        String signingInput = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(signingInput), parts[2])) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Token signature is invalid");
        }

        Map<String, Object> payload = decodeJson(parts[1]);
        if (!issuer.equals(String.valueOf(payload.get("iss")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Token issuer is invalid");
        }

        long expiration = toLong(payload.get("exp"));
        if (Instant.ofEpochSecond(expiration).isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Token has expired");
        }

        String clientIdValue = payload.get("cid") == null ? null : String.valueOf(payload.get("cid"));
        return new VerifiedToken(
                UUID.fromString(String.valueOf(payload.get("sub"))),
                String.valueOf(payload.get("email")),
                UserRole.valueOf(String.valueOf(payload.get("role"))),
                clientIdValue == null || clientIdValue.isBlank() || "null".equalsIgnoreCase(clientIdValue)
                        ? null
                        : UUID.fromString(clientIdValue),
                Instant.ofEpochSecond(expiration)
        );
    }

    public record TokenIssue(String token, Instant expiresAt, long expiresIn) {
    }

    public record VerifiedToken(
            UUID userId,
            String email,
            UserRole role,
            UUID clientId,
            Instant expiresAt
    ) {
    }

    private String encodeJson(Map<String, Object> payload) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize user JWT", exception);
        }
    }

    private Map<String, Object> decodeJson(String encoded) {
        try {
            return objectMapper.readValue(URL_DECODER.decode(encoded), new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Token payload is invalid");
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign user JWT", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected.length() != actual.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expected.length(); i++) {
            result |= expected.charAt(i) ^ actual.charAt(i);
        }
        return result == 0;
    }
}
