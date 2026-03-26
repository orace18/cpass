INSERT INTO providers (id, code, channel, priority, active, config_json, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'AFRICA_TALKING', 'SMS', 3, false, '{"unitCost":0.05}', now(), now());
