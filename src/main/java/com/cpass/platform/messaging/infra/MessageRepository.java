package com.cpass.platform.messaging.infra;

import com.cpass.platform.messaging.domain.MessageEntity;
import com.cpass.platform.messaging.domain.MessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    @Query("""
            SELECT m
            FROM MessageEntity m
            WHERE (:status IS NULL OR m.status = :status)
              AND (:providerCode IS NULL OR UPPER(COALESCE(m.providerCode, '')) = :providerCode)
              AND (:clientId IS NULL OR m.clientId = :clientId)
              AND (:fromAt IS NULL OR m.createdAt >= :fromAt)
              AND (:toAt IS NULL OR m.createdAt < :toAt)
            """)
    Page<MessageEntity> searchAdminMessages(@Param("status") MessageStatus status,
                                            @Param("providerCode") String providerCode,
                                            @Param("clientId") UUID clientId,
                                            @Param("fromAt") Instant fromAt,
                                            @Param("toAt") Instant toAt,
                                            Pageable pageable);

    @Query("""
            SELECT CAST(m.createdAt AS date) AS day, COUNT(m) AS total
            FROM MessageEntity m
            WHERE m.createdAt >= :since
            GROUP BY CAST(m.createdAt AS date)
            ORDER BY CAST(m.createdAt AS date) ASC
            """)
    List<Object[]> dailyVolume(@Param("since") Instant since);
}
