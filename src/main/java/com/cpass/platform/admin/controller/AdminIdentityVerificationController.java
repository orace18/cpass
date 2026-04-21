package com.cpass.platform.admin.controller;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.kyc.domain.IdentityVerificationEntity;
import com.cpass.platform.kyc.domain.VerificationAnalysisEntity;
import com.cpass.platform.kyc.domain.VerificationStatus;
import com.cpass.platform.kyc.dto.AdminVerificationReviewRequest;
import com.cpass.platform.kyc.dto.VerificationResponse;
import com.cpass.platform.kyc.infra.VerificationAnalysisRepository;
import com.cpass.platform.kyc.service.IdentityVerificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "Admin — Identity Verification", description = "Admin management of identity verifications")
@RestController
@RequestMapping("/admin/kyc/identity-verifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminIdentityVerificationController {

    private final IdentityVerificationService identityVerificationService;
    private final VerificationAnalysisRepository verificationAnalysisRepository;

    public AdminIdentityVerificationController(IdentityVerificationService identityVerificationService,
                                               VerificationAnalysisRepository verificationAnalysisRepository) {
        this.identityVerificationService = identityVerificationService;
        this.verificationAnalysisRepository = verificationAnalysisRepository;
    }

    @GetMapping
    public List<VerificationResponse> listVerifications(
            @RequestParam(required = false) VerificationStatus status) {
        return identityVerificationService.listVerifications(status).stream()
                .map(v -> {
                    Optional<VerificationAnalysisEntity> analysis = verificationAnalysisRepository.findByVerification(v);
                    return identityVerificationService.toAdminResponse(v, analysis.orElse(null));
                })
                .toList();
    }

    @GetMapping("/{verificationId}")
    public VerificationResponse getVerification(@PathVariable UUID verificationId) {
        List<IdentityVerificationEntity> all = identityVerificationService.listVerifications(null);
        IdentityVerificationEntity verification = all.stream()
                .filter(v -> v.getId().equals(verificationId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "VERIFICATION_NOT_FOUND", "Identity verification not found"));
        Optional<VerificationAnalysisEntity> analysis = verificationAnalysisRepository.findByVerification(verification);
        return identityVerificationService.toAdminResponse(verification, analysis.orElse(null));
    }

    @PostMapping("/{verificationId}/review")
    public VerificationResponse review(@PathVariable UUID verificationId,
                                       @Valid @RequestBody AdminVerificationReviewRequest request) {
        return identityVerificationService.reviewVerification(verificationId, request);
    }
}
