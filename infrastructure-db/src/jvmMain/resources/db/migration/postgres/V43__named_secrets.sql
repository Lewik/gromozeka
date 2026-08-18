CREATE TABLE named_secrets (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(512) NOT NULL,
    ciphertext TEXT NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    encryption_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, name)
);
