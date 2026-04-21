package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.IdentityVerificationEntity;
import com.cpass.platform.kyc.domain.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerificationEntity, UUID> {

    Optional<IdentityVerificationEntity> findByIdAndClientId(UUID id, UUID clientId);

    List<IdentityVerificationEntity> findAllByOrderByCreatedAtDesc();

    List<IdentityVerificationEntity> findByStatusOrderByCreatedAtDesc(VerificationStatus status);
}
