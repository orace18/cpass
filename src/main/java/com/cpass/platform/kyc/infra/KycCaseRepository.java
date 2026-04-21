package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycCaseRepository extends JpaRepository<KycCaseEntity, UUID> {

    Optional<KycCaseEntity> findByIdAndClientId(UUID id, UUID clientId);

    Optional<KycCaseEntity> findByReferenceIdAndClientId(String referenceId, UUID clientId);

    List<KycCaseEntity> findByIdentityOrderByCreatedAtDesc(IdentityEntity identity);

    List<KycCaseEntity> findByStatusOrderByCreatedAtDesc(KycCaseStatus status);

    List<KycCaseEntity> findAllByOrderByCreatedAtDesc();

    List<KycCaseEntity> findByClientIdOrderByCreatedAtDesc(UUID clientId);

    long countByStatus(KycCaseStatus status);
}
