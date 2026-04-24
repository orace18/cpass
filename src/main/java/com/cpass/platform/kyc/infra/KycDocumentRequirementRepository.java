package com.cpass.platform.kyc.infra;

import com.cpass.platform.kyc.domain.KycDocumentRequirementEntity;
import com.cpass.platform.kyc.domain.KycDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycDocumentRequirementRepository extends JpaRepository<KycDocumentRequirementEntity, UUID> {

    List<KycDocumentRequirementEntity> findByCountryIgnoreCaseAndActiveTrueOrderByDisplayOrderAsc(String country);

    List<KycDocumentRequirementEntity> findByCountryIgnoreCaseOrderByDisplayOrderAsc(String country);

    boolean existsByCountryIgnoreCaseAndActiveTrue(String country);

    List<KycDocumentRequirementEntity> findByCountryIgnoreCaseAndDocumentTypeAndActiveTrueOrderByDisplayOrderAsc(
            String country,
            KycDocumentType documentType
    );
}
