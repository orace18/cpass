package com.cpass.platform.kyc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_kyc")
public class IdentityKycEntity {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "identity_id", nullable = false)
    private IdentityEntity identity;

    @ManyToOne(optional = false)
    @JoinColumn(name = "kyc_case_id", nullable = false)
    private KycCaseEntity kycCase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycCaseStatus status;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public IdentityEntity getIdentity() {
        return identity;
    }

    public void setIdentity(IdentityEntity identity) {
        this.identity = identity;
    }

    public KycCaseEntity getKycCase() {
        return kycCase;
    }

    public void setKycCase(KycCaseEntity kycCase) {
        this.kycCase = kycCase;
    }

    public KycLevel getLevel() {
        return level;
    }

    public void setLevel(KycLevel level) {
        this.level = level;
    }

    public KycCaseStatus getStatus() {
        return status;
    }

    public void setStatus(KycCaseStatus status) {
        this.status = status;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(Instant validUntil) {
        this.validUntil = validUntil;
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
}
