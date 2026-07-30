CREATE TABLE worker_gateway_credentials (
    worker_id TEXT PRIMARY KEY REFERENCES workers(id) ON DELETE CASCADE,
    credential_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_worker_gateway_credentials_active_hash
    ON worker_gateway_credentials(credential_hash)
    WHERE revoked_at IS NULL;
