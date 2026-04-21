package com.cpass.platform.kyc.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
public class KycWorkflowPublisher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KycWorkflowPublisher.class);

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
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(caseId);
                }
            });
            log.info("kyc workflow publish scheduled afterCommit caseId={}", caseId);
            return;
        }
        doPublish(caseId);
    }

    private void doPublish(UUID caseId) {
        if (properties.asyncEnabled()) {
            try {
                rabbitTemplate.convertAndSend(exchange, routingKey, new KycCaseProcessingEvent(caseId));
                log.info("kyc workflow event published caseId={} mode=async exchange={} routingKey={}", caseId, exchange, routingKey);
                return;
            } catch (Exception exception) {
                log.warn("kyc workflow async publish failed caseId={} fallback=inline errorType={} errorMessage={}",
                        caseId,
                        exception.getClass().getName(),
                        exception.getMessage());
            }
        }
        log.info("kyc workflow processing inline caseId={}", caseId);
        kycFlowService.process(caseId);
    }
}
