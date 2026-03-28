package com.cpass.platform.webhook.service;

import com.cpass.platform.webhook.dto.WebhookDispatchMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class WebhookDispatchConsumer {

    private final WebhookDispatchService webhookDispatchService;

    public WebhookDispatchConsumer(WebhookDispatchService webhookDispatchService) {
        this.webhookDispatchService = webhookDispatchService;
    }

    @RabbitListener(queues = "${app.webhooks.messaging.queue:cpass.webhook.queue}")
    public void consume(WebhookDispatchMessage message) {
        webhookDispatchService.process(message.eventId());
    }
}
