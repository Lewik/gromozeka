CREATE TABLE ai_concurrency_groups (
    id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    max_concurrent_calls INTEGER CHECK (max_concurrent_calls BETWEEN 1 AND 64)
);

INSERT INTO ai_concurrency_groups (id, display_name, max_concurrent_calls)
VALUES ('default', 'Default', 4);

UPDATE ai_connections
SET payload_json = (
    jsonb_set(payload_json::jsonb, '{concurrencyGroupId}', '"default"'::jsonb, true)
    #- '{httpSettings,maxConcurrentCalls}'
)::text;

UPDATE runtime_catalog_configuration SET revision = revision + 1;

CREATE TABLE ai_admission_leases (
    id VARCHAR(128) PRIMARY KEY,
    group_id VARCHAR(128) NOT NULL REFERENCES ai_concurrency_groups(id),
    worker_id VARCHAR(255),
    worker_request_id VARCHAR(128),
    background_session_id VARCHAR(128),
    accepted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((worker_id IS NULL) = (worker_request_id IS NULL))
);

CREATE INDEX ai_admission_leases_worker_request ON ai_admission_leases(worker_id, worker_request_id);
