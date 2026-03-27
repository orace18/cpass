package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.IdentityEntity;
import com.cpass.platform.kyc.domain.IdentityKycEntity;
import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityKycRepository extends JpaRepository<IdentityKycEntity, UUID> {

    List<IdentityKycEntity> findByIdentityOrderByCreatedAtDesc(IdentityEntity identity);

    List<IdentityKycEntity> findByIdentityAndStatusOrderByCreatedAtDesc(IdentityEntity identity, KycCaseStatus status);

    Optional<IdentityKycEntity> findByKycCase(KycCaseEntity kycCase);
}
