package com.cpass.platform.kyc.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class KycWorkflowPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final KycFlowProperties properties;
    private final KycFlowService kycFlowService;
    private final String exchange;
    private final String routingKey;

    public KycWorkflowPublisher(RabbitTemplate rabbitTemplate,
                                KycFlowProperties properties,
                                KycFlowService kycFlowService,
                                @Value("${app.kyc.messaging.exchange:cpass.kyc.exchange}") String exchange,
                                @Value("${app.kyc.messaging.routing-key:cpass.kyc.process}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.kycFlowService = kycFlowService;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(UUID caseId) {
        if (properties.asyncEnabled()) {
            try {
                rabbitTemplate.convertAndSend(exchange, routingKey, new KycCaseProcessingEvent(caseId));
                return;
            } catch (Exception ignored) {
                // Fall back to inline processing when the broker is unavailable.
            }
        }
        kycFlowService.process(caseId);
    }
}
