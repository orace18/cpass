package com.cpass.platform.messaging.service;

import com.cpass.platform.billing.service.QuotaService;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.messaging.domain.MessageEntity;
import com.cpass.platform.messaging.domain.MessageStatus;
import com.cpass.platform.messaging.dto.DispatchMessageEvent;
import com.cpass.platform.messaging.dto.MessageResponse;
import com.cpass.platform.messaging.dto.SendSmsRequest;
import com.cpass.platform.messaging.dto.SendWhatsAppRequest;
import com.cpass.platform.messaging.infra.MessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class MessagingService {

    private final MessageRepository messageRepository;
    private final RabbitTemplate rabbitTemplate;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final String dispatchExchange;
    private final String dispatchRoutingKey;

    public MessagingService(MessageRepository messageRepository,
                            RabbitTemplate rabbitTemplate,
                            QuotaService quotaService,
                            ObjectMapper objectMapper,
                            @Value("${app.messaging.exchange}") String dispatchExchange,
                            @Value("${app.messaging.routing-key}") String dispatchRoutingKey) {
        this.messageRepository = messageRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.quotaService = quotaService;
        this.objectMapper = objectMapper;
        this.dispatchExchange = dispatchExchange;
        this.dispatchRoutingKey = dispatchRoutingKey;
    }

    @Transactional
    public MessageResponse enqueueSms(UUID clientId, SendSmsRequest request) {
        return enqueue(clientId, MessageChannel.SMS, request.recipient(), request.rawMessage(), request.templateCode(), request.templateParams());
    }

    @Transactional
    public MessageResponse enqueueWhatsApp(UUID clientId, SendWhatsAppRequest request) {
        return enqueue(clientId, MessageChannel.WHATSAPP, request.recipient(), request.rawMessage(), request.templateCode(), request.templateParams());
    }

    @Transactional
    public MessageResponse enqueue(UUID clientId,
                                   MessageChannel channel,
                                   String recipient,
                                   String rawMessage,
                                   String templateCode,
                                   Map<String, String> templateParams) {
        validatePayload(rawMessage, templateCode);
        quotaService.assertQuota(clientId, channel);

        MessageEntity message = new MessageEntity();
        message.setClientId(clientId);
        message.setChannel(channel);
        message.setRecipient(recipient);
        message.setRawMessage(rawMessage);
        message.setTemplateCode(templateCode);
        message.setTemplateParams(asJson(templateParams));
        message.setStatus(MessageStatus.QUEUED);
        MessageEntity saved = messageRepository.save(message);

        rabbitTemplate.convertAndSend(dispatchExchange, dispatchRoutingKey, new DispatchMessageEvent(saved.getId()));
        return new MessageResponse(saved.getId(), saved.getStatus());
    }

    private void validatePayload(String rawMessage, String templateCode) {
        boolean hasRaw = rawMessage != null && !rawMessage.isBlank();
        boolean hasTemplate = templateCode != null && !templateCode.isBlank();
        if (!hasRaw && !hasTemplate) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_CONTENT_REQUIRED", "Either rawMessage or templateCode must be supplied");
        }
    }

    private String asJson(Map<String, String> templateParams) {
        if (templateParams == null || templateParams.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(templateParams);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE_PARAMS", "Template params must be valid JSON");
        }
    }
}
