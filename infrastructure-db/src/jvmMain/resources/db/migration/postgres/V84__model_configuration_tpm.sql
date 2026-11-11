ALTER TABLE ai_concurrency_groups ADD COLUMN group_key VARCHAR(512);
UPDATE ai_concurrency_groups SET group_key = id;
ALTER TABLE ai_concurrency_groups ALTER COLUMN group_key SET NOT NULL;
ALTER TABLE ai_concurrency_groups ADD CONSTRAINT ai_concurrency_groups_key_unique UNIQUE(group_key);

UPDATE ai_model_configurations AS m
SET payload_json = jsonb_set(m.payload_json::jsonb, '{concurrencyGroupId}',
    COALESCE(c.payload_json::jsonb -> 'concurrencyGroupId', '"default"'::jsonb), true)::text
FROM ai_connections AS c WHERE c.id = m.connection_id;

UPDATE ai_connections SET payload_json = (payload_json::jsonb - 'concurrencyGroupId')::text;
UPDATE runtime_catalog_configuration SET revision = revision + 1;

CREATE TABLE ai_tpm_groups (
    id VARCHAR(128) PRIMARY KEY,
    group_key VARCHAR(512) NOT NULL UNIQUE,
    payload_json TEXT NOT NULL
);

CREATE TABLE ai_tpm_state (
    group_id VARCHAR(128) PRIMARY KEY REFERENCES ai_tpm_groups(id) ON DELETE CASCADE,
    payload_json TEXT NOT NULL
);

ALTER TABLE ai_admission_leases ADD COLUMN tpm_group_id VARCHAR(128) REFERENCES ai_tpm_groups(id);

CREATE TABLE ai_admission_completions (
    id VARCHAR(128) PRIMARY KEY,
    worker_id VARCHAR(255),
    feedback_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ai_admission_completions_created ON ai_admission_completions(created_at);
