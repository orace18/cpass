package com.cpass.platform.client.domain;

import com.cpass.platform.kyc.domain.KycLevel;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Convert;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "clients")
public class ClientEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "secret_hash")
    private String secretHash;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "client_scopes", joinColumns = @JoinColumn(name = "client_id"))
    @Convert(converter = ClientScopeConverter.class)
    @Column(name = "scope", nullable = false)
    private Set<ClientScope> scopes = new LinkedHashSet<>();

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "sms_soft_quota", nullable = false)
    private long smsSoftQuota;

    @Column(name = "sms_hard_quota", nullable = false)
    private long smsHardQuota;

    @Column(name = "whatsapp_soft_quota", nullable = false)
    private long whatsappSoftQuota;

    @Column(name = "whatsapp_hard_quota", nullable = false)
    private long whatsappHardQuota;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "active", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_level_required", nullable = false)
    private KycLevel kycLevelRequired;

    @Column(name = "ip_whitelist", columnDefinition = "TEXT")
    private String ipWhitelist;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (kycLevelRequired == null) {
            kycLevelRequired = KycLevel.LEVEL_0;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public Set<ClientScope> getScopes() {
        return scopes;
    }

    public void setScopes(Set<ClientScope> scopes) {
        this.scopes = scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes);
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public long getSmsSoftQuota() {
        return smsSoftQuota;
    }

    public void setSmsSoftQuota(long smsSoftQuota) {
        this.smsSoftQuota = smsSoftQuota;
    }

    public long getSmsHardQuota() {
        return smsHardQuota;
    }

    public void setSmsHardQuota(long smsHardQuota) {
        this.smsHardQuota = smsHardQuota;
    }

    public long getWhatsappSoftQuota() {
        return whatsappSoftQuota;
    }

    public void setWhatsappSoftQuota(long whatsappSoftQuota) {
        this.whatsappSoftQuota = whatsappSoftQuota;
    }

    public long getWhatsappHardQuota() {
        return whatsappHardQuota;
    }

    public void setWhatsappHardQuota(long whatsappHardQuota) {
        this.whatsappHardQuota = whatsappHardQuota;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isActive() {
        return enabled;
    }

    public void setActive(boolean active) {
        this.enabled = active;
    }

    public KycLevel getKycLevelRequired() {
        return kycLevelRequired;
    }

    public void setKycLevelRequired(KycLevel kycLevelRequired) {
        this.kycLevelRequired = kycLevelRequired;
    }

    public String getIpWhitelist() {
        return ipWhitelist;
    }

    public void setIpWhitelist(String ipWhitelist) {
        this.ipWhitelist = ipWhitelist;
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
