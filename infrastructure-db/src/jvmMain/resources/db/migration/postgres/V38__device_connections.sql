CREATE TABLE device_connections (
    id VARCHAR(255) PRIMARY KEY,
    secret_hash CHAR(64) NOT NULL UNIQUE,
    user_code VARCHAR(16) NOT NULL UNIQUE,
    device_label VARCHAR(255) NOT NULL,
    platform VARCHAR(64) NOT NULL,
    request_client BOOLEAN NOT NULL,
    client_label VARCHAR(255) NULL,
    worker_id VARCHAR(64) NULL,
    worker_kind VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL,
    authorized_user_id VARCHAR(255) NULL REFERENCES users(id) ON DELETE CASCADE,
    decided_by_user_id VARCHAR(255) NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ NULL,
    consumed_at TIMESTAMPTZ NULL,
    CONSTRAINT chk_device_connections_status
        CHECK (status IN ('PENDING', 'APPROVED', 'DENIED', 'CONSUMED', 'EXPIRED')),
    CONSTRAINT chk_device_connections_client
        CHECK (request_client = (client_label IS NOT NULL)),
    CONSTRAINT chk_device_connections_worker
        CHECK (
            (worker_id IS NULL AND worker_kind IS NULL) OR
            (worker_id IS NOT NULL AND worker_kind IN ('EXECUTION', 'MOBILE_DEVICE'))
        ),
    CONSTRAINT chk_device_connections_components
        CHECK (request_client OR worker_id IS NOT NULL),
    CONSTRAINT chk_device_connections_expiration
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_device_connections_pending_expiration
    ON device_connections(expires_at)
    WHERE status IN ('PENDING', 'APPROVED');

CREATE INDEX idx_device_connections_authorized_user
    ON device_connections(authorized_user_id, created_at DESC)
    WHERE authorized_user_id IS NOT NULL;
