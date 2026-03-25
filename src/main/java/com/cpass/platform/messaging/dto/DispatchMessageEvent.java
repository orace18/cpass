package com.cpass.platform.messaging.dto;

import java.io.Serializable;
import java.util.UUID;

public record DispatchMessageEvent(UUID messageId) implements Serializable {
}
