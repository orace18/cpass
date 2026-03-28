package com.cpass.platform.otp.domain;

import com.cpass.platform.messaging.domain.MessageChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "otp")
public class OtpAuditEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "reference_id", nullable = false, unique = true)
    private UUID referenceId;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageChannel channel;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(nullable = false)
    private String salt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtpStatus status;

    @Column(name = "attempts_remaining", nullable = false)
    private int attemptsRemaining;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "provider_code")
    private String providerCode;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getClientId() {
        return clientId;
    }

    public void setClientId(UUID clientId) {
        this.clientId = clientId;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(UUID referenceId) {
        this.referenceId = referenceId;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public MessageChannel getChannel() {
        return channel;
    }

    public void setChannel(MessageChannel channel) {
        this.channel = channel;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public void setOtpHash(String otpHash) {
        this.otpHash = otpHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public OtpStatus getStatus() {
        return status;
    }

    public void setStatus(OtpStatus status) {
        this.status = status;
    }

    public int getAttemptsRemaining() {
        return attemptsRemaining;
    }

    public void setAttemptsRemaining(int attemptsRemaining) {
        this.attemptsRemaining = attemptsRemaining;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }
}
