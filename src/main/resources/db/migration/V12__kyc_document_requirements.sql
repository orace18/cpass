CREATE TABLE IF NOT EXISTS kyc_document_requirements (
    id UUID PRIMARY KEY,
    country VARCHAR(20) NOT NULL,
    document_type VARCHAR(40) NOT NULL,
    label VARCHAR(255) NOT NULL,
    slot VARCHAR(40) NOT NULL,
    slot_label VARCHAR(255) NOT NULL,
    capture_mode VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_kyc_doc_req_country_active
    ON kyc_document_requirements(country, active, display_order);

INSERT INTO kyc_document_requirements (
    id, country, document_type, label, slot, slot_label, capture_mode, active, display_order, created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'DEFAULT', 'PASSPORT', 'Passport', 'IDENTITY_FRONT', 'Document front', 'DOCUMENT', true, 10, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'PASSPORT', 'Passport', 'SELFIE', 'Live selfie', 'LIVE_SELFIE', true, 11, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'NATIONAL_ID', 'National ID', 'IDENTITY_FRONT', 'Document front', 'DOCUMENT', true, 20, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'NATIONAL_ID', 'National ID', 'IDENTITY_BACK', 'Document back', 'DOCUMENT', true, 21, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'NATIONAL_ID', 'National ID', 'SELFIE', 'Live selfie', 'LIVE_SELFIE', true, 22, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'VOTER_CARD', 'Voter card', 'IDENTITY_FRONT', 'Document front', 'DOCUMENT', true, 30, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'VOTER_CARD', 'Voter card', 'IDENTITY_BACK', 'Document back', 'DOCUMENT', true, 31, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'VOTER_CARD', 'Voter card', 'SELFIE', 'Live selfie', 'LIVE_SELFIE', true, 32, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'DRIVER_LICENSE', 'Driver license', 'IDENTITY_FRONT', 'Document front', 'DOCUMENT', true, 40, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'DRIVER_LICENSE', 'Driver license', 'IDENTITY_BACK', 'Document back', 'DOCUMENT', true, 41, now(), now()),
    (gen_random_uuid(), 'DEFAULT', 'DRIVER_LICENSE', 'Driver license', 'SELFIE', 'Live selfie', 'LIVE_SELFIE', true, 42, now(), now()),
    (gen_random_uuid(), 'BJ', 'CIP', 'CIP', 'IDENTITY_FRONT', 'Document front', 'DOCUMENT', true, 10, now(), now()),
    (gen_random_uuid(), 'BJ', 'CIP', 'CIP', 'SELFIE', 'Live selfie', 'LIVE_SELFIE', true, 11, now(), now()),
    (gen_random_uuid(), 'BJ', 'VOTER_CARD', 'Voter card', 'IDENTITY_FRONT', 'Document front', 'DOCUMENT', true, 20, now(), now()),
    (gen_random_uuid(), 'BJ', 'VOTER_CARD', 'Voter card', 'SELFIE', 'Live selfie', 'LIVE_SELFIE', true, 21, now(), now()),
    (gen_random_uuid(), 'BJ', 'SCHOOL_CARD', 'School card', 'IDENTITY_FRONT', 'Document front', 'DOCUMENT', true, 30, now(), now()),
    (gen_random_uuid(), 'BJ', 'SCHOOL_CARD', 'School card', 'SELFIE', 'Live selfie', 'LIVE_SELFIE', true, 31, now(), now());
