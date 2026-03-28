package com.cpass.platform.webhook.service;

import com.cpass.platform.webhook.dto.WebhookDispatchMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
public class WebhookEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final WebhookDispatchService webhookDispatchService;
    private final String exchange;
    private final String routingKey;

    public WebhookEventPublisher(RabbitTemplate rabbitTemplate,
                                 WebhookDispatchService webhookDispatchService,
                                 @Value("${app.webhooks.messaging.exchange:cpass.webhook.exchange}") String exchange,
                                 @Value("${app.webhooks.messaging.routing-key:cpass.webhook.dispatch}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.webhookDispatchService = webhookDispatchService;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publishAfterCommit(UUID eventId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(eventId);
                }
            });
            return;
        }
        publish(eventId);
    }

    public void publish(UUID eventId) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, new WebhookDispatchMessage(eventId));
        } catch (Exception ignored) {
            webhookDispatchService.process(eventId);
        }
    }
}
