package com.cpass.platform.kyc.controller;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.common.security.ClientContextHolder;
import com.cpass.platform.kyc.dto.KycStatusUpdateRequest;
import com.cpass.platform.kyc.dto.KycUploadResponse;
import com.cpass.platform.kyc.service.KycService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/kyc")
public class KycController {

    private final KycService kycService;
    private final String adminToken;

    public KycController(KycService kycService, @Value("${app.admin-token}") String adminToken) {
        this.kycService = kycService;
        this.adminToken = adminToken;
    }

    @PostMapping("/documents")
    public KycUploadResponse upload(@RequestPart("file") MultipartFile file,
                                    @RequestPart(value = "metadata", required = false) String metadata) {
        UUID clientId = ClientContextHolder.requireClientId();
        return kycService.upload(clientId, file, metadata);
    }

    @PatchMapping("/documents/{documentId}/status")
    public KycUploadResponse updateStatus(@RequestHeader("X-ADMIN-TOKEN") String token,
                                          @PathVariable UUID documentId,
                                          @Valid @RequestBody KycStatusUpdateRequest request) {
        if (!adminToken.equals(token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_TOKEN", "Invalid admin token");
        }
        return kycService.updateStatus(documentId, request.status(), request.reason());
    }
}
