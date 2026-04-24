package com.cpass.platform.admin.controller;

import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.dto.AdminKycReviewRequest;
import com.cpass.platform.kyc.dto.KycDocumentRequirementAdminResponse;
import com.cpass.platform.kyc.dto.KycDocumentRequirementRequest;
import com.cpass.platform.kyc.dto.KycOperationalMetricsResponse;
import com.cpass.platform.kyc.service.KycOperationalMetricsService;
import com.cpass.platform.kyc.service.KycRequirementService;
import com.cpass.platform.kyc.service.KycService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/kyc")
@PreAuthorize("hasRole('ADMIN')")
public class AdminKycController {

    private final KycService kycService;
    private final KycOperationalMetricsService metricsService;
    private final KycRequirementService requirementService;
    private final ClientRepository clientRepository;

    public AdminKycController(KycService kycService,
                              KycOperationalMetricsService metricsService,
                              KycRequirementService requirementService,
                              ClientRepository clientRepository) {
        this.kycService = kycService;
        this.metricsService = metricsService;
        this.requirementService = requirementService;
        this.clientRepository = clientRepository;
    }

    @GetMapping("/stats")
    public KycOperationalMetricsResponse stats() {
        return metricsService.snapshot();
    }

    @GetMapping("/requirements")
    public List<KycDocumentRequirementAdminResponse> requirements(@RequestParam(required = false) String country) {
        return requirementService.list(country);
    }

    @PostMapping("/requirements")
    public KycDocumentRequirementAdminResponse createRequirement(@Valid @RequestBody KycDocumentRequirementRequest request) {
        return requirementService.create(request);
    }

    @PutMapping("/requirements/{id}")
    public KycDocumentRequirementAdminResponse updateRequirement(@PathVariable UUID id,
                                                                 @Valid @RequestBody KycDocumentRequirementRequest request) {
        return requirementService.update(id, request);
    }

    @DeleteMapping("/requirements/{id}")
    public void deleteRequirement(@PathVariable UUID id) {
        requirementService.delete(id);
    }

    @GetMapping
    public List<Map<String, Object>> listCases(@RequestParam(required = false) KycCaseStatus status) {
        return kycService.listCases(status).stream()
                .map(this::toMap)
                .toList();
    }

    @PostMapping("/{caseId}/review")
    public Map<String, Object> review(@PathVariable UUID caseId,
                                      @Valid @RequestBody AdminKycReviewRequest request) {
        KycCaseEntity reviewed = kycService.listCases(null).stream()
                .filter(item -> item.getId().equals(caseId))
                .findFirst()
                .orElse(null);
        kycService.reviewCase(caseId, request);
        KycCaseEntity updated = reviewed == null
                ? kycService.listCases(null).stream().filter(item -> item.getId().equals(caseId)).findFirst().orElseThrow()
                : kycService.listCases(null).stream().filter(item -> item.getId().equals(caseId)).findFirst().orElse(reviewed);
        return toMap(updated);
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID documentId,
                                           @RequestParam(required = false) String token) {
        KycService.DownloadedDocument document = kycService.download(documentId);
        return ResponseEntity.ok()
                .contentType(document.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(document.fileName()).build().toString())
                .body(document.content());
    }

    private Map<String, Object> toMap(KycCaseEntity kycCase) {
        List<KycDocumentEntity> documents = kycService.documentsFor(kycCase);
        KycDocumentEntity latestDocument = documents.isEmpty() ? null : documents.get(documents.size() - 1);
        String clientName = clientRepository.findById(kycCase.getClientId()).map(client -> client.getName()).orElse("Unknown client");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", kycCase.getId().toString());
        payload.put("referenceId", kycCase.getReferenceId());
        payload.put("clientId", kycCase.getClientId().toString());
        payload.put("clientName", clientName);
        payload.put("type", latestDocument == null || latestDocument.getDocumentType() == null ? "UNKNOWN" : latestDocument.getDocumentType().name());
        payload.put("status", kycCase.getStatus().name());
        payload.put("level", kycCase.getLevel().name());
        payload.put("submittedAt", kycCase.getCreatedAt().toString());
        payload.put("reviewedAt", latestDocument == null || latestDocument.getReviewedAt() == null ? null : latestDocument.getReviewedAt().toString());
        payload.put("reviewedBy", latestDocument != null && latestDocument.getReviewedAt() != null ? "Admin" : null);
        payload.put("fileUrl", latestDocument == null ? null : "/admin/kyc/documents/" + latestDocument.getId() + "/download");
        payload.put("notes", kycCase.getStatusReason());
        return payload;
    }
}
