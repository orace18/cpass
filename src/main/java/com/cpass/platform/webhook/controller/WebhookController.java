package com.cpass.platform.webhook.controller;

import com.cpass.platform.messaging.domain.MessageEntity;
import com.cpass.platform.messaging.domain.MessageStatus;
import com.cpass.platform.messaging.infra.MessageRepository;
import com.cpass.platform.webhook.dto.DeliveryStatusWebhookRequest;
import com.cpass.platform.webhook.service.ClientWebhookEventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final MessageRepository messageRepository;
    private final ClientWebhookEventService clientWebhookEventService;

    public WebhookController(MessageRepository messageRepository,
                             ClientWebhookEventService clientWebhookEventService) {
        this.messageRepository = messageRepository;
        this.clientWebhookEventService = clientWebhookEventService;
    }

    @PostMapping("/delivery")
    public void updateDeliveryStatus(@Valid @RequestBody DeliveryStatusWebhookRequest request) {
        messageRepository.findById(request.messageId()).ifPresent(message -> {
            message.setProviderMessageId(request.providerMessageId());
            MessageStatus updatedStatus = applyStatusTransition(message, request.status(), request.errorDescription());
            if (updatedStatus == null) {
                return;
            }
            messageRepository.save(message);
            if (updatedStatus == MessageStatus.DELIVERED) {
                clientWebhookEventService.enqueueMessageEvent(message, "message.delivered");
            } else if (updatedStatus == MessageStatus.FAILED) {
                clientWebhookEventService.enqueueMessageEvent(message, "message.failed");
            }
        });
    }

    private MessageStatus applyStatusTransition(MessageEntity message, String requestedStatus, String errorDescription) {
        MessageStatus currentStatus = message.getStatus();

        if ("DELIVERED".equalsIgnoreCase(requestedStatus)) {
            if (currentStatus == MessageStatus.DELIVERED) {
                return null;
            }
            message.setStatus(MessageStatus.DELIVERED);
            message.setFailureReason(null);
            return MessageStatus.DELIVERED;
        }

        if ("FAILED".equalsIgnoreCase(requestedStatus)) {
            if (currentStatus == MessageStatus.DELIVERED) {
                return null;
            }
            if (currentStatus == MessageStatus.FAILED && sameFailureReason(message.getFailureReason(), errorDescription)) {
                return null;
            }
            message.setStatus(MessageStatus.FAILED);
            message.setFailureReason(errorDescription);
            return MessageStatus.FAILED;
        }

        if ("SENT".equalsIgnoreCase(requestedStatus)) {
            if (currentStatus == MessageStatus.QUEUED) {
                message.setStatus(MessageStatus.SENT);
                message.setFailureReason(null);
                return MessageStatus.SENT;
            }
        }

        return null;
    }

    private boolean sameFailureReason(String current, String incoming) {
        if (current == null && incoming == null) {
            return true;
        }
        if (current == null || incoming == null) {
            return false;
        }
        return current.equals(incoming);
    }
}
