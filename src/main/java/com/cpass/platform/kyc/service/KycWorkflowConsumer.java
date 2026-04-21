package com.cpass.platform.kyc.service;

import com.cpass.platform.common.security.RequestCorrelation;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class KycWorkflowConsumer {

    private final KycFlowService kycFlowService;

    public KycWorkflowConsumer(KycFlowService kycFlowService) {
        this.kycFlowService = kycFlowService;
    }

    @RabbitListener(queues = "${app.kyc.messaging.queue:cpass.kyc.queue}")
    public void consume(KycCaseProcessingEvent event) {
        RequestCorrelation.setOrGenerate(event.requestId());
        try {
            kycFlowService.process(event.kycCaseId());
        } finally {
            RequestCorrelation.clear();
        }
    }
}
