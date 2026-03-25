CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE clients (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL UNIQUE,
    webhook_url VARCHAR(500),
    sms_soft_quota BIGINT NOT NULL,
    sms_hard_quota BIGINT NOT NULL,
    whatsapp_soft_quota BIGINT NOT NULL,
    whatsapp_hard_quota BIGINT NOT NULL,
    rate_limit_per_minute INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    api_key VARCHAR(255) NOT NULL UNIQUE,
    secret_encrypted TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    label VARCHAR(150) NOT NULL,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE providers (
    id UUID PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    priority INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    config_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_provider_channel_priority ON providers(channel, priority);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    channel VARCHAR(20) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    template_code VARCHAR(120),
    template_params TEXT,
    raw_message TEXT,
    provider_code VARCHAR(100),
    provider_message_id VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    cost NUMERIC(12,4),
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_messages_client_created ON messages(client_id, created_at);
CREATE INDEX idx_messages_status ON messages(status);

CREATE TABLE otp (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    reference_id UUID NOT NULL UNIQUE,
    destination VARCHAR(255) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    otp_hash VARCHAR(255) NOT NULL,
    salt VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts_remaining INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ
);

CREATE TABLE usage (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    channel VARCHAR(20) NOT NULL,
    period_month DATE NOT NULL,
    units BIGINT NOT NULL,
    total_cost NUMERIC(12,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_usage_client_channel_month UNIQUE (client_id, channel, period_month)
);

CREATE TABLE kyc_documents (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    file_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(600) NOT NULL,
    bucket_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    metadata_json TEXT,
    review_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ
);

INSERT INTO providers (id, code, channel, priority, active, config_json, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'ROUTE_MOBILE', 'SMS', 1, true, '{"unitCost":0.09}', now(), now()),
    (gen_random_uuid(), 'TWILIO', 'SMS', 2, true, '{"unitCost":0.11}', now(), now()),
    (gen_random_uuid(), 'ROUTE_MOBILE', 'WHATSAPP', 1, true, '{"unitCost":0.04}', now(), now()),
    (gen_random_uuid(), 'WHATSAPP_META', 'WHATSAPP', 2, true, '{"unitCost":0.05}', now(), now());
