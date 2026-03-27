package com.cpass.platform.admin.controller;

import com.cpass.platform.billing.domain.UsageRecordEntity;
import com.cpass.platform.billing.infra.UsageRecordRepository;
import com.cpass.platform.client.infra.ClientRepository;
import com.cpass.platform.common.security.AdminSecurityService;
import com.cpass.platform.messaging.domain.MessageChannel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/admin/billing")
public class AdminBillingController {

    private final AdminSecurityService adminSecurityService;
    private final UsageRecordRepository usageRecordRepository;
    private final ClientRepository clientRepository;

    public AdminBillingController(AdminSecurityService adminSecurityService,
                                  UsageRecordRepository usageRecordRepository,
                                  ClientRepository clientRepository) {
        this.adminSecurityService = adminSecurityService;
        this.usageRecordRepository = usageRecordRepository;
        this.clientRepository = clientRepository;
    }

    @GetMapping
    public List<Map<String, Object>> billing(@RequestHeader("X-ADMIN-TOKEN") String headerToken) {
        adminSecurityService.ensureAdminToken(headerToken);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        for (UsageRecordEntity usage : usageRecordRepository.findAll()) {
            String key = usage.getClientId() + ":" + usage.getPeriodMonth();
            Map<String, Object> row = rows.computeIfAbsent(key, ignored -> createRow(usage.getClientId(), usage.getPeriodMonth().format(DateTimeFormatter.ofPattern("yyyy-MM"))));
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
        result.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("month"))).reversed()
                .thenComparing(row -> String.valueOf(row.get("clientName"))));
        result.forEach(row -> row.put("totalCost", ((BigDecimal) row.get("totalCost")).doubleValue()));
        return result;
    }

    private Map<String, Object> createRow(UUID clientId, String month) {
        String clientName = clientRepository.findById(clientId).map(client -> client.getName()).orElse("Unknown client");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", clientId + ":" + month);
        row.put("clientId", clientId.toString());
        row.put("clientName", clientName);
        row.put("month", month);
        row.put("smsCount", 0L);
        row.put("whatsappCount", 0L);
        row.put("smsRate", 0D);
        row.put("whatsappRate", 0D);
        row.put("totalCost", BigDecimal.ZERO);
        return row;
    }

    private double unitRate(UsageRecordEntity usage) {
        if (usage.getUnits() <= 0) {
            return 0D;
        }
        return usage.getTotalCost()
                .divide(BigDecimal.valueOf(usage.getUnits()), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
