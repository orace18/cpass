package com.cpass.platform.otp.infra;

import com.cpass.platform.otp.domain.OtpAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OtpAuditRepository extends JpaRepository<OtpAuditEntity, UUID> {

    Optional<OtpAuditEntity> findByReferenceIdAndClientId(UUID referenceId, UUID clientId);
}
