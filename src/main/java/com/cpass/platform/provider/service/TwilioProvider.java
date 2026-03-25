package com.cpass.platform.provider.service;

import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import org.springframework.stereotype.Component;

@Component
public class TwilioProvider implements MessagingProvider {

    @Override
    public String providerCode() {
        return "TWILIO";
    }

    @Override
    public ProviderDispatchResult send(ProviderMessage message, ProviderConfigEntity providerConfig) {
        return ProviderDispatchResult.failure(providerCode(), "Twilio provider placeholder implementation");
    }
}
