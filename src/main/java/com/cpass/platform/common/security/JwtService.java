package com.cpass.platform.common.security;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.domain.ClientScope;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.kyc.domain.KycLevel;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final String issuer;
    private final long ttlSeconds;

    public JwtService(ObjectMapper objectMapper,
                      @Value("${app.security.jwt.secret}") String secret,
                      @Value("${app.security.jwt.issuer:cpass-platform}") String issuer,
                      @Value("${app.security.jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
        if (ttlSeconds < 900 || ttlSeconds > 3600) {
            throw new IllegalStateException("app.security.jwt.ttl-seconds must be between 900 and 3600");
        }
    }

    public TokenIssue issueToken(ClientEntity client) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);

        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", client.getId().toString());
        payload.put("cid", client.getClientId());
        payload.put("name", client.getName());
        payload.put("scp", client.getScopes().stream().map(ClientScope::scope).toList());
        payload.put("kyc", client.getKycLevelRequired().name());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());

        String token = encodeJson(header) + "." + encodeJson(payload);
        String signature = sign(token);
        return new TokenIssue(token + "." + signature, expiresAt);
    }

    public VerifiedToken verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Token format is invalid");
        }

        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput);
        if (!constantTimeEquals(expectedSignature, parts[2])) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_SIGNATURE", "Token signature is invalid");
        }

        Map<String, Object> payload = decodeJson(parts[1]);
        if (!issuer.equals(payload.get("iss"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_ISSUER", "Token issuer is invalid");
        }

        long expiration = toLong(payload.get("exp"));
        if (Instant.ofEpochSecond(expiration).isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Token has expired");
        }

        Object scopesValue = payload.get("scp");
        if (!(scopesValue instanceof List<?> scopesList)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_SCOPES", "Token scopes are invalid");
        }

        Set<ClientScope> scopes = scopesList.stream()
                .map(String::valueOf)
                .map(ClientScope::fromScope)
                .collect(Collectors.toSet());

        return new VerifiedToken(
                UUID.fromString(String.valueOf(payload.get("sub"))),
                String.valueOf(payload.get("cid")),
                String.valueOf(payload.get("name")),
                scopes,
                KycLevel.valueOf(String.valueOf(payload.get("kyc"))),
                Instant.ofEpochSecond(expiration)
        );
    }

    public record TokenIssue(String token, Instant expiresAt) {
    }

    public record VerifiedToken(
            UUID clientId,
            String clientPublicId,
            String clientName,
            Set<ClientScope> scopes,
            KycLevel kycLevelRequired,
            Instant expiresAt
    ) {
    }

    private String encodeJson(Map<String, Object> payload) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize JWT", exception);
        }
    }

    private Map<String, Object> decodeJson(String encoded) {
        try {
            return objectMapper.readValue(URL_DECODER.decode(encoded), new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN_PAYLOAD", "Token payload is invalid");
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
            throw new IllegalStateException("Unable to sign JWT", exception);
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
