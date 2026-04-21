package com.cpass.platform.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class VerificationWorkflowPublisher {

    private static final Logger log = LoggerFactory.getLogger(VerificationWorkflowPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final VerificationProperties properties;
    private final VerificationFlowService verificationFlowService;
    private final String exchange;
    private final String routingKey;

    public VerificationWorkflowPublisher(RabbitTemplate rabbitTemplate,
                                         VerificationProperties properties,
                                         VerificationFlowService verificationFlowService,
                                         @Value("${app.kyc.verification.messaging.exchange:cpass.identity.verification.exchange}") String exchange,
                                         @Value("${app.kyc.verification.messaging.routing-key:cpass.identity.verification.process}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.verificationFlowService = verificationFlowService;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(UUID verificationId) {
        if (properties.asyncEnabled()) {
            try {
                rabbitTemplate.convertAndSend(exchange, routingKey, new VerificationProcessingEvent(verificationId));
                log.info("verification workflow published async verificationId={}", verificationId);
                return;
            } catch (Exception ex) {
                log.warn("verification workflow broker unavailable — falling back to inline processing verificationId={} error={}",
                        verificationId, ex.getMessage());
            }
        }
        verificationFlowService.process(verificationId);
    }
}
