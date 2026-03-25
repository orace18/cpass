package com.cpass.platform.billing.service;

import com.cpass.platform.client.domain.ClientEntity;
import com.cpass.platform.client.service.ClientService;
import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.messaging.domain.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final UsageService usageService;
    private final ClientService clientService;

    public QuotaService(UsageService usageService, ClientService clientService) {
        this.usageService = usageService;
        this.clientService = clientService;
    }

    public void assertQuota(UUID clientId, MessageChannel channel) {
        ClientEntity client = clientService.getClient(clientId);
        long currentUsage = usageService.currentMonthUsage(clientId, channel);

        long softQuota = channel == MessageChannel.SMS ? client.getSmsSoftQuota() : client.getWhatsappSoftQuota();
        long hardQuota = channel == MessageChannel.SMS ? client.getSmsHardQuota() : client.getWhatsappHardQuota();

        if (currentUsage >= hardQuota) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "HARD_QUOTA_EXCEEDED", channel + " hard quota exceeded");
        }
        if (currentUsage >= softQuota) {
            log.warn("Soft quota reached clientId={} channel={} usage={} softQuota={}",
                    clientId, channel, currentUsage, softQuota);
        }
    }
}
