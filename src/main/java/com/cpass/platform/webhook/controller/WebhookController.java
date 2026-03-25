package com.cpass.platform.webhook.controller;

import com.cpass.platform.messaging.domain.MessageEntity;
import com.cpass.platform.messaging.domain.MessageStatus;
import com.cpass.platform.messaging.infra.MessageRepository;
import com.cpass.platform.webhook.dto.DeliveryStatusWebhookRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final MessageRepository messageRepository;

    public WebhookController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @PostMapping("/delivery")
    public void updateDeliveryStatus(@Valid @RequestBody DeliveryStatusWebhookRequest request) {
        messageRepository.findById(request.messageId()).ifPresent(message -> {
            message.setProviderMessageId(request.providerMessageId());
            if ("DELIVERED".equalsIgnoreCase(request.status())) {
                message.setStatus(MessageStatus.DELIVERED);
                message.setFailureReason(null);
            } else if ("FAILED".equalsIgnoreCase(request.status())) {
                message.setStatus(MessageStatus.FAILED);
                message.setFailureReason(request.errorDescription());
            }
            messageRepository.save(message);
        });
    }
}
