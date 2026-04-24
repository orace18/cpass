package com.cpass.platform.kyc.service;

import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.dto.KycRequirementResponse;
import com.cpass.platform.kyc.infra.KycDocumentRequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KycRequirementServiceTest {

    private KycDocumentRequirementRepository repository;
    private KycRequirementService service;

    @BeforeEach
    void setUp() {
        repository = mock(KycDocumentRequirementRepository.class);
        when(repository.findByCountryIgnoreCaseAndActiveTrueOrderByDisplayOrderAsc("BJ")).thenReturn(List.of());
        when(repository.findByCountryIgnoreCaseAndActiveTrueOrderByDisplayOrderAsc("DEFAULT")).thenReturn(List.of());
        when(repository.existsByCountryIgnoreCaseAndActiveTrue("BJ")).thenReturn(false);
        when(repository.existsByCountryIgnoreCaseAndActiveTrue("FR")).thenReturn(false);
        service = new KycRequirementService(repository);
    }

    @Test
    void shouldReturnBjAcceptedDocuments() {
        KycRequirementResponse response = service.requirementsFor("BJ");

        assertThat(response.country()).isEqualTo("BJ");
        assertThat(response.acceptedDocuments())
                .extracting(KycRequirementResponse.AcceptedDocument::documentType)
                .containsExactly(KycDocumentType.CIP, KycDocumentType.VOTER_CARD, KycDocumentType.SCHOOL_CARD);
    }

    @Test
    void shouldRequireFrontAndSelfieForBjCipWithoutBack() {
        KycRequirementResponse.AcceptedDocument cip = service.requirementsFor("BJ").acceptedDocuments().stream()
                .filter(document -> document.documentType() == KycDocumentType.CIP)
                .findFirst()
                .orElseThrow();

        assertThat(cip.requiredSlots())
                .extracting(KycRequirementResponse.RequiredSlot::slot)
                .containsExactly(KycRequirementService.SLOT_IDENTITY_FRONT, KycRequirementService.SLOT_SELFIE)
                .doesNotContain(KycRequirementService.SLOT_IDENTITY_BACK);
    }

    @Test
    void shouldFallbackToDefaultWhenCountryIsNotConfigured() {
        KycRequirementResponse response = service.requirementsFor("FR");

        assertThat(response.country()).isEqualTo(KycRequirementService.DEFAULT_COUNTRY);
        assertThat(response.acceptedDocuments())
                .extracting(KycRequirementResponse.AcceptedDocument::documentType)
                .contains(KycDocumentType.PASSPORT, KycDocumentType.NATIONAL_ID);
    }
}
