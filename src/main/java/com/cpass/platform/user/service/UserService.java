package com.cpass.platform.user.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.UserPrincipal;
import com.cpass.platform.email.service.EmailService;
import com.cpass.platform.user.domain.PasswordResetPurpose;
import com.cpass.platform.user.domain.PasswordResetTokenEntity;
import com.cpass.platform.user.domain.UserEntity;
import com.cpass.platform.user.domain.UserRole;
import com.cpass.platform.user.dto.CreateUserRequest;
import com.cpass.platform.user.dto.UpdateUserRequest;
import com.cpass.platform.user.dto.UserResponse;
import com.cpass.platform.user.infra.PasswordResetTokenRepository;
import com.cpass.platform.user.infra.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final String frontendBaseUrl;
    private final long resetTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserService(UserRepository userRepository,
                       ClientRepository clientRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       PasswordEncoder passwordEncoder,
                       EmailService emailService,
                       @Value("${app.frontend.base-url:https://cpass-admin.vercel.app}") String frontendBaseUrl,
                       @Value("${app.security.password-reset.ttl-seconds:900}") long resetTtlSeconds) {
        this.userRepository = userRepository;
        this.clientRepository = clientRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.resetTtlSeconds = resetTtlSeconds;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(UserEntity::getCreatedAt).reversed())
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String email = normalizeEmail(request.email());
        assertEmailAvailable(email, null);

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setRole(request.role());
        user.setClientId(resolveClientId(request.role(), request.clientId()));
        user.setEnabled(request.enabled() == null || request.enabled());
        user.setPasswordHash(passwordEncoder.encode(randomToken(24)));

        UserEntity saved = userRepository.save(user);
        inviteUser(saved, PasswordResetPurpose.ONBOARDING);
        return toResponse(saved);
    }

    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        UserEntity user = requireUser(userId);

        if (request.email() != null && !request.email().isBlank()) {
            String email = normalizeEmail(request.email());
            assertEmailAvailable(email, userId);
            user.setEmail(email);
        }

        UserRole role = request.role() == null ? user.getRole() : request.role();
        String clientIdValue = request.clientId() == null && role == user.getRole()
                ? user.getClientId() == null ? null : user.getClientId().toString()
                : request.clientId();

        user.setRole(role);
        user.setClientId(resolveClientId(role, clientIdValue));

        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }

        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserEntity onboardClientOwner(ClientEntity client) {
        String email = normalizeEmail(client.getEmail());
        assertEmailAvailable(email, null);

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setRole(UserRole.CLIENT);
        user.setClientId(client.getId());
        user.setEnabled(client.isEnabled());
        user.setPasswordHash(passwordEncoder.encode(randomToken(24)));

        UserEntity saved = userRepository.save(user);
        inviteUser(saved, PasswordResetPurpose.ONBOARDING);
        return saved;
    }

    @Transactional
    public PasswordTokenIssue issuePasswordToken(UserEntity user, PasswordResetPurpose purpose) {
        Instant now = Instant.now();
        passwordResetTokenRepository.findByUserIdAndUsedAtIsNull(user.getId())
                .forEach(token -> token.setUsedAt(now));

        String rawToken = randomToken(32);
        PasswordResetTokenEntity entity = new PasswordResetTokenEntity();
        entity.setUserId(user.getId());
        entity.setPurpose(purpose);
        entity.setTokenHash(hashToken(rawToken));
        entity.setExpiresAt(now.plusSeconds(resetTtlSeconds));
        passwordResetTokenRepository.save(entity);
        return new PasswordTokenIssue(rawToken, entity.getExpiresAt());
    }

    @Transactional
    public UserEntity consumePasswordToken(String rawToken) {
        PasswordResetTokenEntity token = passwordResetTokenRepository.findByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "Reset token is invalid"));

        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "Reset token is expired");
        }

        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(token);
        return requireUser(token.getUserId());
    }

    @Transactional(readOnly = true)
    public UserEntity requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
    }

    @Transactional(readOnly = true)
    public UserEntity requireByEmail(String email) {
        return userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password"));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(UserPrincipal principal) {
        return toResponse(requireUser(principal.userId()));
    }

    @Transactional(readOnly = true)
    public UserResponse toResponse(UserEntity user) {
        ClientEntity client = user.getClientId() == null ? null : clientRepository.findById(user.getClientId()).orElse(null);
        String clientName = client == null ? null : client.getName();
        String displayName = clientName != null ? clientName : user.getEmail();
        return new UserResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getRole().name(),
                user.getClientId() == null ? null : user.getClientId().toString(),
                clientName,
                user.isEnabled(),
                user.getCreatedAt() == null ? null : user.getCreatedAt().toString(),
                displayName
        );
    }

    public String buildResetLink(String rawToken) {
        return frontendBaseUrl + "/reset-password?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    public void inviteUser(UserEntity user, PasswordResetPurpose purpose) {
        PasswordTokenIssue issue = issuePasswordToken(user, purpose);
        if (purpose == PasswordResetPurpose.ONBOARDING) {
            emailService.sendPasswordSetupEmail(
                    user.getClientId(),
                    user.getEmail(),
                    buildResetLink(issue.rawToken()),
                    user.getRole() == UserRole.CLIENT ? "client" : "admin"
            );
        } else {
            emailService.sendPasswordResetEmail(user.getClientId(), user.getEmail(), buildResetLink(issue.rawToken()));
        }
    }

    private UUID resolveClientId(UserRole role, String clientIdValue) {
        if (role == UserRole.ADMIN) {
            return null;
        }
        if (clientIdValue == null || clientIdValue.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CLIENT_REQUIRED", "Client user must be linked to a client");
        }
        UUID clientId;
        try {
            clientId = UUID.fromString(clientIdValue);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT_ID", "Client identifier is invalid");
        }
        clientRepository.findById(clientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Client not found"));
        return clientId;
    }

    private void assertEmailAvailable(String email, UUID currentUserId) {
        userRepository.findByEmail(email)
                .filter(existing -> currentUserId == null || !existing.getId().equals(currentUserId))
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT, "USER_EMAIL_EXISTS", "A user already exists with this email");
                });
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String randomToken(int bytes) {
        byte[] payload = new byte[bytes];
        secureRandom.nextBytes(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash token", exception);
        }
    }

    public record PasswordTokenIssue(
            String rawToken,
            Instant expiresAt
    ) {
    }
}
