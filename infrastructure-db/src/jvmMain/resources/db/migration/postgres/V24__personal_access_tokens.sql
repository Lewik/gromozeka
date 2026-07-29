CREATE TABLE personal_access_tokens (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    token_prefix VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NULL,
    last_used_at TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL
);

CREATE TABLE personal_access_token_scopes (
    token_id VARCHAR(255) NOT NULL REFERENCES personal_access_tokens(id) ON DELETE CASCADE,
    scope VARCHAR(64) NOT NULL,
    PRIMARY KEY (token_id, scope)
);

CREATE INDEX idx_personal_access_tokens_user
    ON personal_access_tokens(user_id, created_at DESC);

CREATE INDEX idx_personal_access_tokens_active
    ON personal_access_tokens(user_id)
    WHERE revoked_at IS NULL;
