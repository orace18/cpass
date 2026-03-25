package com.cpass.platform.provider.infra;

import com.cpass.platform.messaging.domain.MessageChannel;
import com.cpass.platform.provider.domain.ProviderConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderConfigRepository extends JpaRepository<ProviderConfigEntity, UUID> {

    List<ProviderConfigEntity> findByChannelAndActiveTrueOrderByPriorityAsc(MessageChannel channel);
}
