package com.cpass.platform.webhook.infra;

import com.cpass.platform.webhook.domain.WebhookDeliveryStatus;
import com.cpass.platform.webhook.domain.WebhookEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEventEntity, UUID> {

    Optional<WebhookEventEntity> findByDedupeKey(String dedupeKey);

    List<WebhookEventEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Collection<WebhookDeliveryStatus> statuses,
            Instant nextAttemptAt,
            Pageable pageable
    );

    List<WebhookEventEntity> findByStatusAndLastAttemptAtLessThanEqualOrderByLastAttemptAtAsc(
            WebhookDeliveryStatus status,
            Instant lastAttemptAt,
            Pageable pageable
    );

    @Modifying
    @Transactional
    @Query("""
            update WebhookEventEntity event
               set event.status = :processingStatus,
                   event.attemptCount = event.attemptCount + 1,
                   event.lastAttemptAt = :attemptedAt,
                   event.updatedAt = :attemptedAt
             where event.id = :eventId
               and (
                    (event.status in :eligibleStatuses and event.nextAttemptAt <= :attemptedAt)
                    or
                    (event.status = :staleStatus and event.lastAttemptAt <= :staleBefore)
               )
            """)
    int claimForDispatch(@Param("eventId") UUID eventId,
                         @Param("processingStatus") WebhookDeliveryStatus processingStatus,
                         @Param("eligibleStatuses") Collection<WebhookDeliveryStatus> eligibleStatuses,
                         @Param("attemptedAt") Instant attemptedAt,
                         @Param("staleStatus") WebhookDeliveryStatus staleStatus,
                         @Param("staleBefore") Instant staleBefore);
}
