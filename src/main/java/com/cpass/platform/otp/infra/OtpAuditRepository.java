package com.cpass.platform.otp.infra;

import com.cpass.platform.otp.domain.OtpAuditEntity;
import com.cpass.platform.otp.domain.OtpStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OtpAuditRepository extends JpaRepository<OtpAuditEntity, UUID> {

    Optional<OtpAuditEntity> findByReferenceIdAndClientId(UUID referenceId, UUID clientId);

    List<OtpAuditEntity> findByClientIdAndDestinationAndStatusAndCreatedAtAfter(
            UUID clientId, String destination, OtpStatus status, Instant after);

    long countByClientIdAndDestinationAndCreatedAtAfter(UUID clientId, String destination, Instant after);
}
