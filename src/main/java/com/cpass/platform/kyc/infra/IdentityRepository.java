package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.IdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository extends JpaRepository<IdentityEntity, UUID> {

    Optional<IdentityEntity> findByDocumentNumber(String documentNumber);

    Optional<IdentityEntity> findFirstByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);
}
