CREATE TABLE conversation_runtime_workers (
    worker_id VARCHAR(255) PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    registration_json JSONB NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    last_heartbeat_at TIMESTAMPTZ NOT NULL,
    stopped_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_conversation_runtime_workers_heartbeat
    ON conversation_runtime_workers(last_heartbeat_at DESC);
