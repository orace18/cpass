package com.cpass.platform.kyc.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class VerificationWorkflowConsumer {

    private final VerificationFlowService verificationFlowService;

    public VerificationWorkflowConsumer(VerificationFlowService verificationFlowService) {
        this.verificationFlowService = verificationFlowService;
    }

    @RabbitListener(queues = "${app.kyc.verification.messaging.queue:cpass.identity.verification.queue}")
    public void consume(VerificationProcessingEvent event) {
        verificationFlowService.process(event.verificationId());
    }
}
