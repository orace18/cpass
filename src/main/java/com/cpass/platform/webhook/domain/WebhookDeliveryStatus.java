package com.cpass.platform.webhook.domain;

public enum WebhookDeliveryStatus {
    PENDING,
    PROCESSING,
    RETRY,
    DELIVERED,
    FAILED
}
