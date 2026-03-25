package com.cpass.platform.messaging.controller;

import com.cpass.platform.common.security.ClientContextHolder;
import com.cpass.platform.messaging.dto.MessageResponse;
import com.cpass.platform.messaging.dto.SendSmsRequest;
import com.cpass.platform.messaging.dto.SendWhatsAppRequest;
import com.cpass.platform.messaging.service.MessagingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/messages")
public class MessagingController {

    private final MessagingService messagingService;

    public MessagingController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @PostMapping("/sms")
    public MessageResponse sendSms(@Valid @RequestBody SendSmsRequest request) {
        UUID clientId = ClientContextHolder.requireClientId();
        return messagingService.enqueueSms(clientId, request);
    }

    @PostMapping("/whatsapp")
    public MessageResponse sendWhatsApp(@Valid @RequestBody SendWhatsAppRequest request) {
        UUID clientId = ClientContextHolder.requireClientId();
        return messagingService.enqueueWhatsApp(clientId, request);
    }
}
