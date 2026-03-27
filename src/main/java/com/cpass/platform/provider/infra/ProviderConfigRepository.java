package com.cpass.platform.provider.infra;

import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import com.cpass.platform.provider.domain.ProviderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderConfigRepository extends JpaRepository<ProviderConfigEntity, UUID> {

    List<ProviderConfigEntity> findByProviderTypeAndChannelAndActiveTrueOrderByPriorityAsc(ProviderType providerType, MessageChannel channel);

    List<ProviderConfigEntity> findByProviderTypeAndActiveTrueOrderByPriorityAsc(ProviderType providerType);

    Optional<ProviderConfigEntity> findByProviderTypeAndCodeAndChannel(ProviderType providerType, String code, MessageChannel channel);

    Optional<ProviderConfigEntity> findByProviderTypeAndCode(ProviderType providerType, String code);
}
