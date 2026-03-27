package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.KycCaseEntity;
import com.cpass.platform.kyc.domain.KycDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocumentEntity, UUID> {

    List<KycDocumentEntity> findByKycCaseOrderByCreatedAtAsc(KycCaseEntity kycCase);
}
