package com.cpass.platform.messaging.infra;

import com.cpass.platform.messaging.domain.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID>, JpaSpecificationExecutor<MessageEntity> {

    @Query("""
            SELECT CAST(m.createdAt AS date) AS day, COUNT(m) AS total
            FROM MessageEntity m
            WHERE m.createdAt >= :since
            GROUP BY CAST(m.createdAt AS date)
            ORDER BY CAST(m.createdAt AS date) ASC
            """)
    List<Object[]> dailyVolume(@Param("since") Instant since);
}
