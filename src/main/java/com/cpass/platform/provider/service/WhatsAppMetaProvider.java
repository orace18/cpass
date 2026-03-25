package com.cpass.platform.provider.service;

import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppMetaProvider implements MessagingProvider {

    @Override
    public String providerCode() {
        return "WHATSAPP_META";
    }

    @Override
    public ProviderDispatchResult send(ProviderMessage message, ProviderConfigEntity providerConfig) {
        return ProviderDispatchResult.failure(providerCode(), "Meta WhatsApp provider placeholder implementation");
    }
}
