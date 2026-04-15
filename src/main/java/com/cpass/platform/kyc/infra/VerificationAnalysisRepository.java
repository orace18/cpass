package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.IdentityVerificationEntity;
import com.cpass.platform.kyc.domain.VerificationAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VerificationAnalysisRepository extends JpaRepository<VerificationAnalysisEntity, UUID> {

    Optional<VerificationAnalysisEntity> findByVerification(IdentityVerificationEntity verification);
}
