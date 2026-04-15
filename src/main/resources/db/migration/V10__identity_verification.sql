-- ============================================================
-- V10: MVP Identity Verification tables
-- ============================================================
-- Three tables:
--   identity_verifications  — main verification record per client request
--   verification_documents  — S3 keys for uploaded files
--   verification_analysis   — server-side OCR + face match results
-- ============================================================

CREATE TABLE identity_verifications (
    id               UUID PRIMARY KEY,
    client_id        UUID         NOT NULL,
    reference_id     VARCHAR(255),
    status           VARCHAR(50)  NOT NULL,
    current_step     VARCHAR(50),
    document_type    VARCHAR(50)  NOT NULL,
    first_name       VARCHAR(255),
    last_name        VARCHAR(255),
    phone_number     VARCHAR(50),
    reliability_score INTEGER,
    decision_reason  VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_identity_verifications_client_id ON identity_verifications (client_id);
CREATE INDEX idx_identity_verifications_status    ON identity_verifications (status);

-- ============================================================

CREATE TABLE verification_documents (
    id                  UUID PRIMARY KEY,
    verification_id     UUID         NOT NULL REFERENCES identity_verifications (id),
    document_front_key  VARCHAR(512) NOT NULL,
    document_back_key   VARCHAR(512),
    selfie_key          VARCHAR(512) NOT NULL,
    bucket_name         VARCHAR(255) NOT NULL,
    storage_provider    VARCHAR(50)  NOT NULL,
    capture_device      VARCHAR(255),
    app_version         VARCHAR(100),
    platform            VARCHAR(100),
    uploaded_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_verification_documents_verification_id ON verification_documents (verification_id);

-- ============================================================

CREATE TABLE verification_analysis (
    id                        UUID PRIMARY KEY,
    verification_id           UUID    NOT NULL UNIQUE REFERENCES identity_verifications (id),
    document_readable         BOOLEAN,
    ocr_confidence            INTEGER,
    extracted_full_name       VARCHAR(512),
    extracted_document_number VARCHAR(255),
    extracted_birth_date      DATE,
    extracted_expiry_date     DATE,
    extracted_nationality     VARCHAR(100),
    document_expired          BOOLEAN,
    face_match_score          INTEGER,
    face_matched              BOOLEAN,
    score_details_json        TEXT,
    analyzed_at               TIMESTAMPTZ NOT NULL
);
