CREATE TABLE ai_user_credentials (
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    connection_id VARCHAR(255) NOT NULL REFERENCES ai_connections(id) ON DELETE CASCADE,
    ciphertext TEXT NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    encryption_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, connection_id)
);
