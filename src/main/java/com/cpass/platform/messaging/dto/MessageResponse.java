package com.cpass.platform.messaging.dto;

import com.cpass.platform.messaging.domain.MessageStatus;

import java.util.UUID;

public record MessageResponse(UUID messageId, MessageStatus status) {
}
