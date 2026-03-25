package com.cpass.platform.provider.service;

import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderMessage;
import com.cpass.platform.provider.infra.ProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProviderRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRoutingService.class);

    private final ProviderConfigRepository providerConfigRepository;
    private final Map<String, MessagingProvider> providersByCode;

    public ProviderRoutingService(ProviderConfigRepository providerConfigRepository,
                                  List<MessagingProvider> providers) {
        this.providerConfigRepository = providerConfigRepository;
        this.providersByCode = providers.stream().collect(Collectors.toMap(MessagingProvider::providerCode, Function.identity()));
    }

    public ProviderDispatchResult dispatch(ProviderMessage message) {
        List<ProviderConfigEntity> candidates = providerConfigRepository
                .findByChannelAndActiveTrueOrderByPriorityAsc(message.channel());

        if (candidates.isEmpty()) {
            return ProviderDispatchResult.failure("NONE", "No active provider configured for " + message.channel());
        }

        ProviderDispatchResult lastFailure = ProviderDispatchResult.failure("NONE", "No provider tried");
        for (ProviderConfigEntity candidate : candidates) {
            MessagingProvider provider = providersByCode.get(candidate.getCode());
            if (provider == null) {
                lastFailure = ProviderDispatchResult.failure(candidate.getCode(), "Provider bean not found");
                continue;
            }

            ProviderDispatchResult attempt = provider.send(message, candidate);
            if (attempt.success()) {
                return attempt;
            }
            log.warn("Provider dispatch failed provider={} channel={} recipient={} reason={}",
                    candidate.getCode(), message.channel(), message.recipient(), attempt.error());
            lastFailure = attempt;
        }
        return lastFailure;
    }

    public List<ProviderConfigEntity> activeProviders(MessageChannel channel) {
        return providerConfigRepository.findByChannelAndActiveTrueOrderByPriorityAsc(channel);
    }
}
