package com.cpass.platform.client.controller;

import com.cpass.platform.admin.dto.AdminMessageListResponse;
import com.cpass.platform.admin.service.AdminMessageService;
import com.cpass.platform.billing.domain.UsageRecordEntity;
import com.cpass.platform.billing.infra.UsageRecordRepository;
import com.cpass.platform.client.dto.ClientCredentialsResponse;
import com.cpass.platform.client.dto.ClientEmailUpdateRequest;
import com.cpass.platform.client.dto.ClientWebhookUpdateRequest;
import com.cpass.platform.client.dto.UpdateClientRequest;
import com.cpass.platform.client.service.ClientService;
import com.cpass.platform.common.security.UserContextHolder;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import com.cpass.platform.kyc.infra.KycCaseRepository;
import com.cpass.platform.kyc.service.KycService;
import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.otp.domain.OtpAuditEntity;
import com.cpass.platform.otp.infra.OtpAuditRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/me")
@PreAuthorize("hasRole('CLIENT')")
public class ClientDashboardController {

    private final AdminMessageService adminMessageService;
    private final UsageRecordRepository usageRecordRepository;
    private final OtpAuditRepository otpAuditRepository;
    private final KycCaseRepository kycCaseRepository;
    private final KycService kycService;
    private final ClientService clientService;

    public ClientDashboardController(AdminMessageService adminMessageService,
                                     UsageRecordRepository usageRecordRepository,
                                     OtpAuditRepository otpAuditRepository,
                                     KycCaseRepository kycCaseRepository,
                                     KycService kycService,
                                     ClientService clientService) {
        this.adminMessageService = adminMessageService;
        this.usageRecordRepository = usageRecordRepository;
        this.otpAuditRepository = otpAuditRepository;
        this.kycCaseRepository = kycCaseRepository;
        this.kycService = kycService;
        this.clientService = clientService;
    }

    // ─── Monitoring ───────────────────────────────────────────────────────────

    @GetMapping("/messages")
    public AdminMessageListResponse messages(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID clientId = UserContextHolder.requireClientId();
        return adminMessageService.listMessages(page, pageSize, status, null, clientId, from, to);
    }

    // ─── Billing ─────────────────────────────────────────────────────────────

    @GetMapping("/billing")
    public List<Map<String, Object>> billing() {
        UUID clientId = UserContextHolder.requireClientId();
        List<UsageRecordEntity> records = usageRecordRepository.findByClientIdOrderByPeriodMonthDesc(clientId);

        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (UsageRecordEntity usage : records) {
            String key = usage.getPeriodMonth().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            Map<String, Object> row = rows.computeIfAbsent(key, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", clientId + ":" + k);
                r.put("clientId", clientId.toString());
                r.put("month", k);
                r.put("smsCount", 0L);
                r.put("whatsappCount", 0L);
                r.put("smsRate", 0D);
                r.put("whatsappRate", 0D);
                r.put("totalCost", BigDecimal.ZERO);
                return r;
            });
            if (usage.getChannel() == MessageChannel.SMS) {
                row.put("smsCount", usage.getUnits());
                row.put("smsRate", unitRate(usage));
            } else if (usage.getChannel() == MessageChannel.WHATSAPP) {
                row.put("whatsappCount", usage.getUnits());
                row.put("whatsappRate", unitRate(usage));
            }
            row.put("totalCost", ((BigDecimal) row.get("totalCost")).add(usage.getTotalCost()));
        }

        List<Map<String, Object>> result = new ArrayList<>(rows.values());
        result.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("month"))).reversed());
        result.forEach(r -> r.put("totalCost", ((BigDecimal) r.get("totalCost")).doubleValue()));
        return result;
    }

    // ─── OTP logs ─────────────────────────────────────────────────────────────

    @GetMapping("/otp/logs")
    public Map<String, Object> otpLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        UUID clientId = UserContextHolder.requireClientId();
        int resolvedPage = Math.max(1, page);
        int resolvedPageSize = Math.min(Math.max(1, pageSize), 100);

        var result = otpAuditRepository.findByClientIdOrderByCreatedAtDesc(
                clientId,
                PageRequest.of(resolvedPage - 1, resolvedPageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> data = result.getContent().stream()
                .map(this::otpToMap)
                .toList();

        return Map.of("data", data, "total", result.getTotalElements(), "page", resolvedPage, "pageSize", resolvedPageSize);
    }

    // ─── KYC ─────────────────────────────────────────────────────────────────

    @GetMapping("/kyc")
    public List<Map<String, Object>> kyc() {
        UUID clientId = UserContextHolder.requireClientId();
        return kycCaseRepository.findByClientIdOrderByCreatedAtDesc(clientId).stream()
                .map(this::kycToMap)
                .toList();
    }

    // ─── Alerts (placeholder – no backend engine yet) ─────────────────────────

    @GetMapping("/alerts")
    public List<Object> alerts() {
        return List.of();
    }

    // ─── Client settings ──────────────────────────────────────────────────────

    @GetMapping("/client")
    public Map<String, Object> getClient() {
        UUID clientId = UserContextHolder.requireClientId();
        return clientService.toPublicMap(clientService.getClient(clientId));
    }

    @PostMapping("/client/regenerate-secret")
    public ClientCredentialsResponse regenerateSecret() {
        UUID clientId = UserContextHolder.requireClientId();
        return clientService.regenerateSecret(clientId);
    }

    @PatchMapping("/client/webhook")
    public Map<String, Object> updateWebhook(@Valid @RequestBody ClientWebhookUpdateRequest request) {
        UUID clientId = UserContextHolder.requireClientId();
        UpdateClientRequest update = new UpdateClientRequest(
                null, null,
                request.webhookUrl(),
                request.webhookEnabled(),
                null, null, null, null, null, null, null, null, null);
        return clientService.toPublicMap(clientService.updateClient(clientId, update));
    }

    @PatchMapping("/client/email")
    public Map<String, Object> updateEmail(@Valid @RequestBody ClientEmailUpdateRequest request) {
        UUID clientId = UserContextHolder.requireClientId();
        UpdateClientRequest update = new UpdateClientRequest(
                null, request.email(),
                null, null, null, null, null, null, null, null, null, null, null);
        return clientService.toPublicMap(clientService.updateClient(clientId, update));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> otpToMap(OtpAuditEntity otp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", otp.getId().toString());
        m.put("clientId", otp.getClientId().toString());
        m.put("phone", otp.getDestination());
        m.put("channel", otp.getChannel() == null ? "UNKNOWN" : otp.getChannel().name());
        m.put("status", otp.getStatus() == null ? "UNKNOWN" : otp.getStatus().name());
        int maxAttempts = 3;
        int attemptsUsed = maxAttempts - otp.getAttemptsRemaining();
        m.put("attempts", Math.max(0, attemptsUsed));
        m.put("flagged", attemptsUsed > maxAttempts);
        m.put("createdAt", otp.getCreatedAt() == null ? null : otp.getCreatedAt().toString());
        return m;
    }

    private Map<String, Object> kycToMap(KycCaseEntity kycCase) {
        List<KycDocumentEntity> documents = kycService.documentsFor(kycCase);
        KycDocumentEntity latest = documents.isEmpty() ? null : documents.get(documents.size() - 1);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kycCase.getId().toString());
        m.put("referenceId", kycCase.getReferenceId());
        m.put("clientId", kycCase.getClientId().toString());
        m.put("type", latest == null || latest.getDocumentType() == null ? "UNKNOWN" : latest.getDocumentType().name());
        m.put("status", kycCase.getStatus().name());
        m.put("level", kycCase.getLevel().name());
        m.put("submittedAt", kycCase.getCreatedAt().toString());
        m.put("reviewedAt", latest == null || latest.getReviewedAt() == null ? null : latest.getReviewedAt().toString());
        m.put("fileUrl", latest == null ? null : "/admin/kyc/documents/" + latest.getId() + "/download");
        m.put("notes", kycCase.getStatusReason());
        return m;
    }

    private double unitRate(UsageRecordEntity usage) {
        if (usage.getUnits() <= 0) return 0D;
        return usage.getTotalCost()
                .divide(BigDecimal.valueOf(usage.getUnits()), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
