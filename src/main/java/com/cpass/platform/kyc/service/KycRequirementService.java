package com.cpass.platform.kyc.service;

import com.cpass.platform.common.exception.ApiException;
import com.cpass.platform.kyc.domain.KycDocumentRequirementEntity;
import com.cpass.platform.kyc.domain.KycDocumentType;
import com.cpass.platform.kyc.dto.KycDocumentRequirementAdminResponse;
import com.cpass.platform.kyc.dto.KycDocumentRequirementRequest;
import com.cpass.platform.kyc.dto.KycRequirementResponse;
import com.cpass.platform.kyc.infra.KycDocumentRequirementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class KycRequirementService {

    public static final String DEFAULT_COUNTRY = "DEFAULT";
    public static final String SLOT_IDENTITY_FRONT = "IDENTITY_FRONT";
    public static final String SLOT_IDENTITY_BACK = "IDENTITY_BACK";
    public static final String SLOT_SELFIE = "SELFIE";

    private final KycDocumentRequirementRepository repository;

    public KycRequirementService(KycDocumentRequirementRepository repository) {
        this.repository = repository;
    }

    public KycRequirementResponse requirementsFor(String country) {
        String resolvedCountry = resolveCountry(country);
        return toResponse(resolvedCountry, activeRowsFor(resolvedCountry));
    }

    public boolean hasActiveCountryConfig(String country) {
        String normalized = normalizeCountry(country);
        return normalized != null
                && !DEFAULT_COUNTRY.equals(normalized)
                && (repository.existsByCountryIgnoreCaseAndActiveTrue(normalized) || hasBuiltInCountry(normalized));
    }

    public boolean isAcceptedDocument(String country, KycDocumentType documentType) {
        return activeRowsFor(resolveCountry(country)).stream()
                .anyMatch(row -> row.getDocumentType() == documentType);
    }

    public List<String> requiredSlotsFor(String country, KycDocumentType documentType) {
        String resolvedCountry = resolveCountry(country);
        List<String> slots = activeRowsFor(resolvedCountry).stream()
                .filter(row -> row.getDocumentType() == documentType)
                .map(row -> normalizeSlot(row.getSlot()))
                .distinct()
                .toList();
        if (slots.isEmpty() && !DEFAULT_COUNTRY.equals(resolvedCountry)) {
            return requiredSlotsFor(DEFAULT_COUNTRY, documentType);
        }
        return slots;
    }

    public List<KycDocumentRequirementAdminResponse> list(String country) {
        List<KycDocumentRequirementEntity> rows = country == null || country.isBlank()
                ? repository.findAll().stream()
                .sorted(Comparator.comparing(KycDocumentRequirementEntity::getCountry)
                        .thenComparingInt(KycDocumentRequirementEntity::getDisplayOrder))
                .toList()
                : repository.findByCountryIgnoreCaseOrderByDisplayOrderAsc(normalizeCountry(country));
        return rows.stream().map(this::toAdminResponse).toList();
    }

    @Transactional
    public KycDocumentRequirementAdminResponse create(KycDocumentRequirementRequest request) {
        KycDocumentRequirementEntity entity = new KycDocumentRequirementEntity();
        apply(entity, request);
        return toAdminResponse(repository.save(entity));
    }

    @Transactional
    public KycDocumentRequirementAdminResponse update(UUID id, KycDocumentRequirementRequest request) {
        KycDocumentRequirementEntity entity = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KYC_REQUIREMENT_NOT_FOUND", "KYC requirement not found"));
        apply(entity, request);
        return toAdminResponse(repository.save(entity));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "KYC_REQUIREMENT_NOT_FOUND", "KYC requirement not found");
        }
        repository.deleteById(id);
    }

    private String resolveCountry(String country) {
        String normalized = normalizeCountry(country);
        if (normalized != null
                && !DEFAULT_COUNTRY.equals(normalized)
                && (repository.existsByCountryIgnoreCaseAndActiveTrue(normalized) || hasBuiltInCountry(normalized))) {
            return normalized;
        }
        return DEFAULT_COUNTRY;
    }

    private List<KycDocumentRequirementEntity> activeRowsFor(String country) {
        List<KycDocumentRequirementEntity> rows = repository.findByCountryIgnoreCaseAndActiveTrueOrderByDisplayOrderAsc(country);
        if (!rows.isEmpty()) {
            return rows;
        }
        return builtInRows(country);
    }

    private boolean hasBuiltInCountry(String country) {
        return "BJ".equalsIgnoreCase(country);
    }

    private KycRequirementResponse toResponse(String country, List<KycDocumentRequirementEntity> rows) {
        Map<KycDocumentType, List<KycDocumentRequirementEntity>> byDocument = new LinkedHashMap<>();
        for (KycDocumentRequirementEntity row : rows) {
            byDocument.computeIfAbsent(row.getDocumentType(), ignored -> new ArrayList<>()).add(row);
        }
        List<KycRequirementResponse.AcceptedDocument> documents = byDocument.entrySet().stream()
                .map(entry -> new KycRequirementResponse.AcceptedDocument(
                        entry.getKey(),
                        entry.getValue().getFirst().getLabel(),
                        entry.getValue().stream()
                                .map(row -> new KycRequirementResponse.RequiredSlot(
                                        normalizeSlot(row.getSlot()),
                                        row.getSlotLabel(),
                                        row.getCaptureMode()
                                ))
                                .toList()
                ))
                .toList();
        return new KycRequirementResponse(country, documents);
    }

    private List<KycDocumentRequirementEntity> builtInRows(String country) {
        if ("BJ".equalsIgnoreCase(country)) {
            return List.of(
                    row("BJ", KycDocumentType.CIP, "CIP", SLOT_IDENTITY_FRONT, "Document front", "DOCUMENT", true, 10),
                    row("BJ", KycDocumentType.CIP, "CIP", SLOT_SELFIE, "Live selfie", "LIVE_SELFIE", true, 11),
                    row("BJ", KycDocumentType.VOTER_CARD, "Voter card", SLOT_IDENTITY_FRONT, "Document front", "DOCUMENT", true, 20),
                    row("BJ", KycDocumentType.VOTER_CARD, "Voter card", SLOT_SELFIE, "Live selfie", "LIVE_SELFIE", true, 21),
                    row("BJ", KycDocumentType.SCHOOL_CARD, "School card", SLOT_IDENTITY_FRONT, "Document front", "DOCUMENT", true, 30),
                    row("BJ", KycDocumentType.SCHOOL_CARD, "School card", SLOT_SELFIE, "Live selfie", "LIVE_SELFIE", true, 31)
            );
        }
        return List.of(
                row(DEFAULT_COUNTRY, KycDocumentType.PASSPORT, "Passport", SLOT_IDENTITY_FRONT, "Document front", "DOCUMENT", true, 10),
                row(DEFAULT_COUNTRY, KycDocumentType.PASSPORT, "Passport", SLOT_SELFIE, "Live selfie", "LIVE_SELFIE", true, 11),
                row(DEFAULT_COUNTRY, KycDocumentType.NATIONAL_ID, "National ID", SLOT_IDENTITY_FRONT, "Document front", "DOCUMENT", true, 20),
                row(DEFAULT_COUNTRY, KycDocumentType.NATIONAL_ID, "National ID", SLOT_IDENTITY_BACK, "Document back", "DOCUMENT", true, 21),
                row(DEFAULT_COUNTRY, KycDocumentType.NATIONAL_ID, "National ID", SLOT_SELFIE, "Live selfie", "LIVE_SELFIE", true, 22),
                row(DEFAULT_COUNTRY, KycDocumentType.VOTER_CARD, "Voter card", SLOT_IDENTITY_FRONT, "Document front", "DOCUMENT", true, 30),
                row(DEFAULT_COUNTRY, KycDocumentType.VOTER_CARD, "Voter card", SLOT_IDENTITY_BACK, "Document back", "DOCUMENT", true, 31),
                row(DEFAULT_COUNTRY, KycDocumentType.VOTER_CARD, "Voter card", SLOT_SELFIE, "Live selfie", "LIVE_SELFIE", true, 32),
                row(DEFAULT_COUNTRY, KycDocumentType.DRIVER_LICENSE, "Driver license", SLOT_IDENTITY_FRONT, "Document front", "DOCUMENT", true, 40),
                row(DEFAULT_COUNTRY, KycDocumentType.DRIVER_LICENSE, "Driver license", SLOT_IDENTITY_BACK, "Document back", "DOCUMENT", true, 41),
                row(DEFAULT_COUNTRY, KycDocumentType.DRIVER_LICENSE, "Driver license", SLOT_SELFIE, "Live selfie", "LIVE_SELFIE", true, 42)
        );
    }

    private KycDocumentRequirementEntity row(String country,
                                             KycDocumentType documentType,
                                             String label,
                                             String slot,
                                             String slotLabel,
                                             String captureMode,
                                             boolean active,
                                             int displayOrder) {
        KycDocumentRequirementEntity row = new KycDocumentRequirementEntity();
        row.setCountry(country);
        row.setDocumentType(documentType);
        row.setLabel(label);
        row.setSlot(slot);
        row.setSlotLabel(slotLabel);
        row.setCaptureMode(captureMode);
        row.setActive(active);
        row.setDisplayOrder(displayOrder);
        return row;
    }

    private void apply(KycDocumentRequirementEntity entity, KycDocumentRequirementRequest request) {
        entity.setCountry(normalizeCountry(request.country()));
        entity.setDocumentType(request.documentType());
        entity.setLabel(request.label());
        entity.setSlot(normalizeSlot(request.slot()));
        entity.setSlotLabel(request.slotLabel());
        entity.setCaptureMode(request.captureMode().toUpperCase(Locale.ROOT));
        entity.setActive(request.active() == null || request.active());
        entity.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
    }

    private KycDocumentRequirementAdminResponse toAdminResponse(KycDocumentRequirementEntity entity) {
        return new KycDocumentRequirementAdminResponse(
                entity.getId(),
                entity.getCountry(),
                entity.getDocumentType(),
                entity.getLabel(),
                normalizeSlot(entity.getSlot()),
                entity.getSlotLabel(),
                entity.getCaptureMode(),
                entity.isActive(),
                entity.getDisplayOrder()
        );
    }

    public String normalizeCountry(String country) {
        if (country == null || country.isBlank()) {
            return DEFAULT_COUNTRY;
        }
        return country.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizeSlot(String slot) {
        if (slot == null || slot.isBlank()) {
            return null;
        }
        String normalized = slot.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.contains("BACK") || normalized.contains("VERSO")) {
            return SLOT_IDENTITY_BACK;
        }
        if (normalized.contains("SELFIE")) {
            return SLOT_SELFIE;
        }
        if (normalized.contains("FRONT") || normalized.contains("RECTO")) {
            return SLOT_IDENTITY_FRONT;
        }
        return normalized;
    }
}
