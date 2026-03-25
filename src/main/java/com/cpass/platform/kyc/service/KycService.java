package com.cpass.platform.kyc.service;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.domain.KycStatus;
import com.cpass.platform.kyc.dto.KycUploadResponse;
import com.cpass.platform.kyc.infra.KycDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.util.UUID;

@Service
public class KycService {

    private final S3Client s3Client;
    private final KycDocumentRepository kycDocumentRepository;
    private final String bucketName;

    public KycService(S3Client s3Client,
                      KycDocumentRepository kycDocumentRepository,
                      @Value("${app.kyc.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.kycDocumentRepository = kycDocumentRepository;
        this.bucketName = bucketName;
    }

    @Transactional
    public KycUploadResponse upload(UUID clientId, MultipartFile file, String metadataJson) {
        try {
            String objectKey = clientId + "/" + Instant.now().toEpochMilli() + "-" + file.getOriginalFilename();
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(file.getBytes()));

            KycDocumentEntity document = new KycDocumentEntity();
            document.setClientId(clientId);
            document.setFileName(file.getOriginalFilename());
            document.setObjectKey(objectKey);
            document.setBucketName(bucketName);
            document.setContentType(file.getContentType());
            document.setSizeBytes(file.getSize());
            document.setStatus(KycStatus.PENDING);
            document.setMetadataJson(metadataJson);
            KycDocumentEntity saved = kycDocumentRepository.save(document);

            return new KycUploadResponse(saved.getId(), saved.getStatus(), saved.getObjectKey());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "KYC_UPLOAD_FAILED", "Failed to upload KYC document");
        }
    }

    @Transactional
    public KycUploadResponse updateStatus(UUID documentId, KycStatus status, String reason) {
        KycDocumentEntity document = kycDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_DOCUMENT_NOT_FOUND", "KYC document not found"));
        document.setStatus(status);
        document.setReviewReason(reason);
        document.setReviewedAt(Instant.now());
        KycDocumentEntity saved = kycDocumentRepository.save(document);
        return new KycUploadResponse(saved.getId(), saved.getStatus(), saved.getObjectKey());
    }
}
