CREATE TABLE ai_tool_contracts (
    fingerprint VARCHAR(64) PRIMARY KEY,
    logical_name VARCHAR(255) NOT NULL,
    model_name VARCHAR(64) NOT NULL UNIQUE,
    variant INTEGER NOT NULL CHECK (variant > 0),
    source_id VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (logical_name, variant)
);

CREATE INDEX idx_ai_tool_contracts_logical_name
    ON ai_tool_contracts (logical_name);
