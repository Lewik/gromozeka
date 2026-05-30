CREATE TABLE conversation_runtime_records (
    conversation_id TEXT PRIMARY KEY,
    record_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversation_runtime_records_updated_at
    ON conversation_runtime_records(updated_at);
