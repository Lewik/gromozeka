CREATE TABLE mcp_servers (
    id VARCHAR(64) PRIMARY KEY,
    worker_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    refresh_available BOOLEAN NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_mcp_servers_revision_positive CHECK (revision > 0)
);

CREATE INDEX idx_mcp_servers_worker
    ON mcp_servers(worker_id, id);

CREATE TABLE ai_tool_capability_catalogs (
    source_id VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    model_configuration_id VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_id, fingerprint)
);

CREATE INDEX idx_ai_tool_capability_catalogs_generated_at
    ON ai_tool_capability_catalogs(generated_at);
