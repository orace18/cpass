package com.cpass.platform.webhook.service;

import com.cpass.platform.webhook.domain.WebhookDeliveryStatus;
import com.cpass.platform.webhook.infra.WebhookEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class WebhookRetryScheduler {

    private static final java.time.Duration PROCESSING_TIMEOUT = java.time.Duration.ofMinutes(10);

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventPublisher webhookEventPublisher;

    public WebhookRetryScheduler(WebhookEventRepository webhookEventRepository,
                                 WebhookEventPublisher webhookEventPublisher) {
        this.webhookEventRepository = webhookEventRepository;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    @Scheduled(fixedDelayString = "${app.webhooks.retry.fixed-delay-ms:60000}")
    public void scheduleDueEvents() {
        Instant now = Instant.now();
        webhookEventRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRY),
                        now,
                        PageRequest.of(0, 100)
                )
                .forEach(event -> webhookEventPublisher.publish(event.getId()));
        webhookEventRepository.findByStatusAndLastAttemptAtLessThanEqualOrderByLastAttemptAtAsc(
                        WebhookDeliveryStatus.PROCESSING,
                        now.minus(PROCESSING_TIMEOUT),
                        PageRequest.of(0, 100)
                )
                .forEach(event -> webhookEventPublisher.publish(event.getId()));
    }
}
