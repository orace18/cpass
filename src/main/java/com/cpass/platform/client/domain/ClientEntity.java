package com.cpass.platform.client.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clients")
public class ClientEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

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

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        active = true;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
