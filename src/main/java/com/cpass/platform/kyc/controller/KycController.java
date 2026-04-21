package com.cpass.platform.kyc.controller;

import com.cpass.platform.common.security.ClientContextHolder;
import com.cpass.platform.common.security.ClientPrincipal;
import com.cpass.platform.common.security.RequestCorrelation;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.dto.KycCaseResponse;
import com.cpass.platform.kyc.dto.KycCheckOrInitRequest;
import com.cpass.platform.kyc.dto.KycCheckOrInitResponse;
import com.cpass.platform.kyc.dto.KycContactVerificationRequest;
import com.cpass.platform.kyc.dto.KycFieldVerificationRequest;
import com.cpass.platform.kyc.dto.KycSocialValidationRequest;
import com.cpass.platform.kyc.dto.KycUploadResponse;
import com.cpass.platform.kyc.dto.KycVerifyRequest;
import com.cpass.platform.kyc.service.KycService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/kyc")
public class KycController {

    private static final Logger log = LoggerFactory.getLogger(KycController.class);

    private final KycService kycService;

    public KycController(KycService kycService) {
        this.kycService = kycService;
    }

    @PostMapping("/check-or-init")
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycCheckOrInitResponse checkOrInit(@Valid @RequestBody KycCheckOrInitRequest request) {
        ClientPrincipal principal = ClientContextHolder.requireClient();
        return kycService.checkOrInit(principal.clientId(), principal.kycLevelRequired(), request);
    }

    @PostMapping("/init")
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycCheckOrInitResponse init(@Valid @RequestBody KycCheckOrInitRequest request) {
        ClientPrincipal principal = ClientContextHolder.requireClient();
        return kycService.checkOrInit(principal.clientId(), principal.kycLevelRequired(), request);
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasAuthority('SCOPE_kyc:read')")
    public KycCaseResponse getCase(@PathVariable UUID caseId) {
        return kycService.getCase(ClientContextHolder.requireClientId(), caseId);
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('SCOPE_kyc:read')")
    public KycCaseResponse getStatus(@RequestParam UUID caseId) {
        UUID clientId = ClientContextHolder.requireClientId();
        log.info("kyc status request requestId={} clientId={} caseId={}", RequestCorrelation.requestId(), clientId, caseId);
        return kycService.getCase(clientId, caseId);
    }

    @PostMapping("/cases/{caseId}/contact-verify")
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycCaseResponse verifyContact(@PathVariable UUID caseId,
                                         @Valid @RequestBody KycContactVerificationRequest request) {
        return kycService.verifyContact(ClientContextHolder.requireClientId(), caseId, request);
    }

    @PostMapping(value = "/cases/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycUploadResponse upload(@PathVariable UUID caseId,
                                    @RequestParam KycDocumentType documentType,
                                    @RequestPart("file") MultipartFile file,
                                    @RequestPart(value = "metadata", required = false) String metadata) {
        logUploadRequest("case-upload", caseId, documentType, file, metadata);
        return kycService.upload(ClientContextHolder.requireClientId(), caseId, documentType, file, metadata);
    }

    @PostMapping(value = "/submit-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycUploadResponse submitDocument(@RequestParam UUID caseId,
                                            @RequestParam KycDocumentType documentType,
                                            @RequestPart("file") MultipartFile file,
                                            @RequestPart(value = "metadata", required = false) String metadata) {
        logUploadRequest("submit-document", caseId, documentType, file, metadata);
        return kycService.upload(ClientContextHolder.requireClientId(), caseId, documentType, file, metadata);
    }

    @PostMapping("/cases/{caseId}/field-verification")
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycCaseResponse recordFieldVerification(@PathVariable UUID caseId,
                                                   @Valid @RequestBody KycFieldVerificationRequest request) {
        return kycService.recordFieldVerification(ClientContextHolder.requireClientId(), caseId, request);
    }

    @PostMapping("/cases/{caseId}/social-validation")
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycCaseResponse recordSocialValidation(@PathVariable UUID caseId,
                                                  @Valid @RequestBody KycSocialValidationRequest request) {
        return kycService.recordSocialValidation(ClientContextHolder.requireClientId(), caseId, request);
    }

    @PostMapping(value = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycCaseResponse verify(@Valid @RequestBody KycVerifyRequest request) {
        return kycService.verifyContact(
                ClientContextHolder.requireClientId(),
                request.caseId(),
                new KycContactVerificationRequest(request.otpReferenceId(), request.otpCode())
        );
    }

    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_kyc:write')")
    public KycCaseResponse verifyMvp(@Valid @RequestPart("identity") KycCheckOrInitRequest identity,
                                     @RequestParam KycDocumentType documentType,
                                     @RequestPart("document") MultipartFile document,
                                     @RequestPart("selfie") MultipartFile selfie,
                                     @RequestPart(value = "documentMetadata", required = false) String documentMetadata,
                                     @RequestPart(value = "selfieMetadata", required = false) String selfieMetadata) {
        ClientPrincipal principal = ClientContextHolder.requireClient();
        if (!isSupportedIdentityDocument(documentType)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_DOCUMENT_TYPE",
                    "documentType must be PASSPORT, NATIONAL_ID, VOTER_CARD or DRIVER_LICENSE"
            );
        }

        log.info("kyc verify request requestId={} clientId={} documentType={} userId={} documentFile={} selfieFile={}",
                RequestCorrelation.requestId(),
                principal.clientId(),
                documentType,
                identity.userId(),
                document.getOriginalFilename(),
                selfie.getOriginalFilename());

        KycCheckOrInitResponse init = kycService.checkOrInit(principal.clientId(), principal.kycLevelRequired(), identity);
        kycService.upload(principal.clientId(), init.kycCaseId(), documentType, document, documentMetadata);
        kycService.upload(principal.clientId(), init.kycCaseId(), KycDocumentType.SELFIE, selfie, selfieMetadata);
        KycCaseResponse response = kycService.getCase(principal.clientId(), init.kycCaseId());

        log.info("kyc verify completed requestId={} clientId={} caseId={} caseStatus={} productStatus={} reasonCode={} nextAction={}",
                RequestCorrelation.requestId(),
                principal.clientId(),
                init.kycCaseId(),
                response.caseStatus(),
                response.productStatus(),
                response.reasonCode(),
                response.nextAction());
        return response;
    }

    private boolean isSupportedIdentityDocument(KycDocumentType documentType) {
        return documentType == KycDocumentType.PASSPORT
                || documentType == KycDocumentType.NATIONAL_ID
                || documentType == KycDocumentType.VOTER_CARD
                || documentType == KycDocumentType.DRIVER_LICENSE;
    }

    private void logUploadRequest(String route,
                                  UUID caseId,
                                  KycDocumentType documentType,
                                  MultipartFile file,
                                  String metadata) {
        log.info("kyc upload request requestId={} route={} caseId={} documentType={} contentType={} fileSize={} originalFilename={} metadata={}",
                RequestCorrelation.requestId(),
                route,
                caseId,
                documentType,
                file.getContentType(),
                file.getSize(),
                file.getOriginalFilename(),
                metadata);
    }
}
