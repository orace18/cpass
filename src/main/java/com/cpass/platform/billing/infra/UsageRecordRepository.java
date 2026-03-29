package com.cpass.platform.billing.infra;

import com.cpass.platform.billing.domain.UsageRecordEntity;
import com.cpass.platform.messaging.domain.MessageChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, UUID> {

    Optional<UsageRecordEntity> findByClientIdAndChannelAndPeriodMonth(UUID clientId, MessageChannel channel, LocalDate periodMonth);

    List<UsageRecordEntity> findByClientIdOrderByPeriodMonthDesc(UUID clientId);
}
