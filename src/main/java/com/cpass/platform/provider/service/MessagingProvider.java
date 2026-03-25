package com.cpass.platform.provider.service;

import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;

public interface MessagingProvider {

    String providerCode();

    ProviderDispatchResult send(ProviderMessage message, ProviderConfigEntity providerConfig);
}
