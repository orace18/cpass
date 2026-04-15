package com.cpass.platform.kyc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "verification_analysis")
public class VerificationAnalysisEntity {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "verification_id", nullable = false, unique = true)
    private IdentityVerificationEntity verification;

    @Column(name = "document_readable")
    private Boolean documentReadable;

    @Column(name = "ocr_confidence")
    private Integer ocrConfidence;

    @Column(name = "extracted_full_name")
    private String extractedFullName;

    @Column(name = "extracted_document_number")
    private String extractedDocumentNumber;

    @Column(name = "extracted_birth_date")
    private LocalDate extractedBirthDate;

    @Column(name = "extracted_expiry_date")
    private LocalDate extractedExpiryDate;

    @Column(name = "extracted_nationality")
    private String extractedNationality;

    @Column(name = "document_expired")
    private Boolean documentExpired;

    @Column(name = "face_match_score")
    private Integer faceMatchScore;

    @Column(name = "face_matched")
    private Boolean faceMatched;

    @Column(name = "score_details_json", columnDefinition = "TEXT")
    private String scoreDetailsJson;

    @Column(name = "analyzed_at", nullable = false, updatable = false)
    private Instant analyzedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        analyzedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public IdentityVerificationEntity getVerification() { return verification; }
    public void setVerification(IdentityVerificationEntity verification) { this.verification = verification; }

    public Boolean getDocumentReadable() { return documentReadable; }
    public void setDocumentReadable(Boolean documentReadable) { this.documentReadable = documentReadable; }

    public Integer getOcrConfidence() { return ocrConfidence; }
    public void setOcrConfidence(Integer ocrConfidence) { this.ocrConfidence = ocrConfidence; }

    public String getExtractedFullName() { return extractedFullName; }
    public void setExtractedFullName(String extractedFullName) { this.extractedFullName = extractedFullName; }

    public String getExtractedDocumentNumber() { return extractedDocumentNumber; }
    public void setExtractedDocumentNumber(String extractedDocumentNumber) { this.extractedDocumentNumber = extractedDocumentNumber; }

    public LocalDate getExtractedBirthDate() { return extractedBirthDate; }
    public void setExtractedBirthDate(LocalDate extractedBirthDate) { this.extractedBirthDate = extractedBirthDate; }

    public LocalDate getExtractedExpiryDate() { return extractedExpiryDate; }
    public void setExtractedExpiryDate(LocalDate extractedExpiryDate) { this.extractedExpiryDate = extractedExpiryDate; }

    public String getExtractedNationality() { return extractedNationality; }
    public void setExtractedNationality(String extractedNationality) { this.extractedNationality = extractedNationality; }

    public Boolean getDocumentExpired() { return documentExpired; }
    public void setDocumentExpired(Boolean documentExpired) { this.documentExpired = documentExpired; }

    public Integer getFaceMatchScore() { return faceMatchScore; }
    public void setFaceMatchScore(Integer faceMatchScore) { this.faceMatchScore = faceMatchScore; }

    public Boolean getFaceMatched() { return faceMatched; }
    public void setFaceMatched(Boolean faceMatched) { this.faceMatched = faceMatched; }

    public String getScoreDetailsJson() { return scoreDetailsJson; }
    public void setScoreDetailsJson(String scoreDetailsJson) { this.scoreDetailsJson = scoreDetailsJson; }

    public Instant getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(Instant analyzedAt) { this.analyzedAt = analyzedAt; }
}
