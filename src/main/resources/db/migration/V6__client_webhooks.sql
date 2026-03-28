ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS webhook_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS webhook_secret_encrypted TEXT;

ALTER TABLE otp
    ADD COLUMN IF NOT EXISTS provider_code VARCHAR(100);

CREATE TABLE IF NOT EXISTS webhook_events (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    event_type VARCHAR(80) NOT NULL,
    dedupe_key VARCHAR(255) NOT NULL UNIQUE,
    message_id UUID,
    otp_reference_id UUID,
    status VARCHAR(20) NOT NULL,
    channel VARCHAR(20),
    provider VARCHAR(100),
    payload_json TEXT NOT NULL,
    webhook_url VARCHAR(500) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_http_status INTEGER,
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_webhook_events_due
    ON webhook_events (status, next_attempt_at);
