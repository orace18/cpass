ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS client_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS secret_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS kyc_level_required VARCHAR(20) NOT NULL DEFAULT 'LEVEL_0',
    ADD COLUMN IF NOT EXISTS ip_whitelist TEXT;

UPDATE clients
SET email = regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g') || '@cpass.local'
WHERE email IS NULL;

UPDATE clients
SET client_id = 'cl_' || replace(id::text, '-', '')
WHERE client_id IS NULL;

ALTER TABLE clients
    ALTER COLUMN email SET NOT NULL,
    ALTER COLUMN client_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_clients_email') THEN
        ALTER TABLE clients ADD CONSTRAINT uk_clients_email UNIQUE (email);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_clients_client_id') THEN
        ALTER TABLE clients ADD CONSTRAINT uk_clients_client_id UNIQUE (client_id);
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS client_scopes (
    client_id UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    scope VARCHAR(50) NOT NULL,
    PRIMARY KEY (client_id, scope)
);

INSERT INTO client_scopes (client_id, scope)
SELECT c.id, s.scope
FROM clients c
CROSS JOIN (
    VALUES ('sms:send'), ('whatsapp:send'), ('otp:send'), ('kyc:read'), ('kyc:write')
) AS s(scope)
ON CONFLICT DO NOTHING;

ALTER TABLE providers
    ADD COLUMN IF NOT EXISTS provider_type VARCHAR(20) NOT NULL DEFAULT 'MESSAGING';

ALTER TABLE providers
    ALTER COLUMN channel DROP NOT NULL;

UPDATE providers
SET provider_type = 'MESSAGING'
WHERE provider_type IS NULL;

INSERT INTO providers (id, provider_type, code, channel, priority, active, config_json, created_at, updated_at)
SELECT gen_random_uuid(), 'KYC', 'EXTERNAL_KYC_FALLBACK', NULL, 1, true, '{"approvalThreshold":65,"reviewThreshold":45}', now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM providers WHERE provider_type = 'KYC' AND code = 'EXTERNAL_KYC_FALLBACK'
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY,
    actor_type VARCHAR(30) NOT NULL,
    actor_id VARCHAR(255),
    client_id UUID,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    result_status INTEGER NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    ip_address VARCHAR(64),
    called_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS identities (
    id UUID PRIMARY KEY,
    document_number VARCHAR(120),
    phone_number VARCHAR(50),
    full_name VARCHAR(255),
    face_hash VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_identities_document_number ON identities(document_number) WHERE document_number IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_identities_phone_number ON identities(phone_number);

CREATE TABLE IF NOT EXISTS kyc_cases (
    id UUID PRIMARY KEY,
    reference_id VARCHAR(120) NOT NULL UNIQUE,
    client_id UUID NOT NULL REFERENCES clients(id),
    identity_id UUID NOT NULL REFERENCES identities(id),
    user_id VARCHAR(120) NOT NULL,
    level VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    score INTEGER,
    document_expiry_date DATE,
    status_reason TEXT,
    valid_until TIMESTAMPTZ,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_kyc_cases_client_status ON kyc_cases(client_id, status);
CREATE INDEX IF NOT EXISTS idx_kyc_cases_identity ON kyc_cases(identity_id);

CREATE TABLE IF NOT EXISTS identity_kyc (
    id UUID PRIMARY KEY,
    identity_id UUID NOT NULL REFERENCES identities(id),
    kyc_case_id UUID NOT NULL REFERENCES kyc_cases(id) ON DELETE CASCADE,
    level VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_identity_kyc_case UNIQUE (kyc_case_id)
);

CREATE INDEX IF NOT EXISTS idx_identity_kyc_identity_status ON identity_kyc(identity_id, status, valid_until);

ALTER TABLE kyc_documents
    ADD COLUMN IF NOT EXISTS kyc_case_id UUID REFERENCES kyc_cases(id),
    ADD COLUMN IF NOT EXISTS document_type VARCHAR(40),
    ADD COLUMN IF NOT EXISTS preview_path VARCHAR(500),
    ADD COLUMN IF NOT EXISTS document_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS expiry_date DATE,
    ADD COLUMN IF NOT EXISTS validity_status VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_kyc_documents_case ON kyc_documents(kyc_case_id);
