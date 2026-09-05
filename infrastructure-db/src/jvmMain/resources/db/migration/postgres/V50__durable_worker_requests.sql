CREATE TABLE worker_requests (
    id VARCHAR(64) PRIMARY KEY,
    worker_id VARCHAR(255) NOT NULL REFERENCES workers(id) ON DELETE RESTRICT,
    actor_user_id VARCHAR(255) NULL REFERENCES users(id) ON DELETE RESTRICT,
    -- Retain the origin after Project deletion so pending requests still fail authorization.
    project_id VARCHAR(255) NULL,
    request_ciphertext TEXT NOT NULL,
    request_nonce TEXT NOT NULL,
    request_version INTEGER NOT NULL,
    response_ciphertext TEXT NULL,
    response_nonce TEXT NULL,
    response_version INTEGER NULL,
    created_at TIMESTAMPTZ NOT NULL,
    start_deadline TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ NULL,
    cancel_requested_at TIMESTAMPTZ NULL,
    completed_at TIMESTAMPTZ NULL,
    CONSTRAINT chk_worker_request_deadline CHECK (start_deadline > created_at),
    CONSTRAINT chk_worker_request_result CHECK (
        (completed_at IS NULL AND response_ciphertext IS NULL AND response_nonce IS NULL AND response_version IS NULL) OR
        (completed_at IS NOT NULL AND response_ciphertext IS NOT NULL AND response_nonce IS NOT NULL AND response_version IS NOT NULL)
    )
);

CREATE INDEX idx_worker_requests_pending ON worker_requests(worker_id, created_at) WHERE completed_at IS NULL;
