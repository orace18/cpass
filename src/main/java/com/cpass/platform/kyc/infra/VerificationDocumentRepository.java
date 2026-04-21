package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.IdentityVerificationEntity;
import com.cpass.platform.kyc.domain.VerificationDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VerificationDocumentRepository extends JpaRepository<VerificationDocumentEntity, UUID> {

    Optional<VerificationDocumentEntity> findByVerification(IdentityVerificationEntity verification);
}
