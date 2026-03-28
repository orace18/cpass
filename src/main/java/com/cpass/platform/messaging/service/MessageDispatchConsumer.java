package com.cpass.platform.messaging.service;

import com.cpass.platform.billing.service.UsageService;
import com.cpass.platform.messaging.domain.MessageEntity;
import com.cpass.platform.messaging.domain.MessageStatus;
import com.cpass.platform.messaging.dto.DispatchMessageEvent;
import com.cpass.platform.messaging.infra.MessageRepository;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.cpass.platform.provider.service.ProviderDispatchResult;
import com.cpass.platform.provider.service.ProviderRoutingService;
import com.cpass.platform.webhook.service.ClientWebhookEventService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;

@Component
public class MessageDispatchConsumer {

    private final MessageRepository messageRepository;
    private final ProviderRoutingService providerRoutingService;
    private final UsageService usageService;
    private final ObjectMapper objectMapper;
    private final ClientWebhookEventService clientWebhookEventService;

    public MessageDispatchConsumer(MessageRepository messageRepository,
                                   ProviderRoutingService providerRoutingService,
                                   UsageService usageService,
                                   ObjectMapper objectMapper,
                                   ClientWebhookEventService clientWebhookEventService) {
        this.messageRepository = messageRepository;
        this.providerRoutingService = providerRoutingService;
        this.usageService = usageService;
        this.objectMapper = objectMapper;
        this.clientWebhookEventService = clientWebhookEventService;
    }

    @RabbitListener(queues = "${app.messaging.queue}")
    @Transactional
    public void consume(DispatchMessageEvent event) {
        MessageEntity message = messageRepository.findById(event.messageId()).orElse(null);
        if (message == null || message.getStatus() != MessageStatus.QUEUED) {
            return;
        }

        ProviderDispatchResult dispatchResult = providerRoutingService.dispatch(new ProviderMessage(
                message.getClientId(),
                message.getChannel(),
                message.getRecipient(),
                message.getRawMessage(),
                message.getTemplateCode(),
                parseParams(message.getTemplateParams())
        ));

        message.setProviderCode(dispatchResult.providerCode());
        if (dispatchResult.success()) {
            message.setProviderMessageId(dispatchResult.providerMessageId());
            message.setStatus(MessageStatus.SENT);
            message.setCost(dispatchResult.cost());
            usageService.recordUsage(message.getClientId(), message.getChannel(), dispatchResult.cost());
        } else {
            message.setStatus(MessageStatus.FAILED);
            message.setFailureReason(dispatchResult.error());
        }
        messageRepository.save(message);
        if (dispatchResult.success()) {
            clientWebhookEventService.enqueueMessageEvent(message, "message.sent");
        } else {
            clientWebhookEventService.enqueueMessageEvent(message, "message.failed");
        }
    }

    private Map<String, String> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Collections.emptyMap();
        }
    }
}
