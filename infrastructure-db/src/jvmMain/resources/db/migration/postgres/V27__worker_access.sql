CREATE TABLE workers (
    id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    owner_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    organization_access BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_workers_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_workers_owner ON workers(owner_user_id);

CREATE TABLE worker_user_grants (
    worker_id VARCHAR(64) NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    PRIMARY KEY (worker_id, user_id)
);

CREATE INDEX idx_worker_user_grants_user ON worker_user_grants(user_id);

CREATE TABLE worker_project_grants (
    worker_id VARCHAR(64) NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    PRIMARY KEY (worker_id, project_id)
);

CREATE INDEX idx_worker_project_grants_project ON worker_project_grants(project_id);

CREATE TABLE worker_enrollment_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    owner_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_worker_enrollment_tokens_expiration
    ON worker_enrollment_tokens(expires_at)
    WHERE consumed_at IS NULL;
