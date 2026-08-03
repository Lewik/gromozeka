CREATE TABLE artifacts (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    created_by_user_id VARCHAR(255) REFERENCES users(id) ON DELETE SET NULL,
    file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 VARCHAR(64) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    committed_at TIMESTAMPTZ,
    CONSTRAINT artifacts_state_committed_at_check CHECK (
        (state = 'DRAFT' AND committed_at IS NULL) OR
        (state = 'COMMITTED' AND committed_at IS NOT NULL)
    )
);

CREATE INDEX artifacts_conversation_created_at_idx
    ON artifacts(conversation_id, created_at);

CREATE INDEX artifacts_draft_gc_idx
    ON artifacts(created_at)
    WHERE state = 'DRAFT';
