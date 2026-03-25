package com.cpass.platform.billing.service;

import com.cpass.platform.billing.domain.UsageRecordEntity;
import com.cpass.platform.billing.infra.UsageRecordRepository;
import com.cpass.platform.messaging.domain.MessageChannel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class UsageService {

    private final UsageRecordRepository usageRecordRepository;

    public UsageService(UsageRecordRepository usageRecordRepository) {
        this.usageRecordRepository = usageRecordRepository;
    }

    @Transactional
    public void recordUsage(UUID clientId, MessageChannel channel, BigDecimal unitCost) {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        UsageRecordEntity usage = usageRecordRepository.findByClientIdAndChannelAndPeriodMonth(clientId, channel, month)
                .orElseGet(() -> {
                    UsageRecordEntity entity = new UsageRecordEntity();
                    entity.setClientId(clientId);
                    entity.setChannel(channel);
                    entity.setPeriodMonth(month);
                    entity.setUnits(0);
                    entity.setTotalCost(BigDecimal.ZERO);
                    return entity;
                });

        usage.setUnits(usage.getUnits() + 1);
        usage.setTotalCost(usage.getTotalCost().add(unitCost == null ? BigDecimal.ZERO : unitCost));
        usageRecordRepository.save(usage);
    }

    @Transactional(readOnly = true)
    public long currentMonthUsage(UUID clientId, MessageChannel channel) {
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        return usageRecordRepository.findByClientIdAndChannelAndPeriodMonth(clientId, channel, month)
                .map(UsageRecordEntity::getUnits)
                .orElse(0L);
    }
}
