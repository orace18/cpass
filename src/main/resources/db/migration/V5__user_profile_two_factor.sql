ALTER TABLE platform_users
    ADD COLUMN IF NOT EXISTS two_factor_email VARCHAR(255);

UPDATE platform_users
SET two_factor_email = email
WHERE two_factor_email IS NULL OR btrim(two_factor_email) = '';

ALTER TABLE platform_users
    ALTER COLUMN two_factor_email SET NOT NULL;
