package com.cpass.platform.messaging.infra;

import com.cpass.platform.messaging.domain.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {
}
