package com.cpass.platform.kyc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_documents")
public class VerificationDocumentEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verification_id", nullable = false)
    private IdentityVerificationEntity verification;

    @Column(name = "document_front_key", nullable = false)
    private String documentFrontKey;

    @Column(name = "document_back_key")
    private String documentBackKey;

    @Column(name = "selfie_key", nullable = false)
    private String selfieKey;

    @Column(name = "bucket_name", nullable = false)
    private String bucketName;

    @Column(name = "storage_provider", nullable = false)
    private String storageProvider;

    @Column(name = "capture_device")
    private String captureDevice;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "platform")
    private String platform;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        uploadedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public IdentityVerificationEntity getVerification() { return verification; }
    public void setVerification(IdentityVerificationEntity verification) { this.verification = verification; }

    public String getDocumentFrontKey() { return documentFrontKey; }
    public void setDocumentFrontKey(String documentFrontKey) { this.documentFrontKey = documentFrontKey; }

    public String getDocumentBackKey() { return documentBackKey; }
    public void setDocumentBackKey(String documentBackKey) { this.documentBackKey = documentBackKey; }

    public String getSelfieKey() { return selfieKey; }
    public void setSelfieKey(String selfieKey) { this.selfieKey = selfieKey; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getStorageProvider() { return storageProvider; }
    public void setStorageProvider(String storageProvider) { this.storageProvider = storageProvider; }

    public String getCaptureDevice() { return captureDevice; }
    public void setCaptureDevice(String captureDevice) { this.captureDevice = captureDevice; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
