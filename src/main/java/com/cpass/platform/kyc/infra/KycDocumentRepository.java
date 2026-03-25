package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.KycDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocumentEntity, UUID> {
}
